package com.skyble.tube;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * High quality YouTube files keep picture and sound apart. This fetches both
 * tracks and joins them into one normal mp4 so the result plays anywhere.
 */
final class Muxer {

    interface Listener {
        /** bytes done / expected total (0 when unknown), plus a short stage name */
        void progress(long done, long total, String stage);

        /** finished and saved; uri may be empty */
        void done(String uri, String name, long bytes);

        /** could not finish */
        void fail(String message);
    }

    /** one running job, so the UI can stop it */
    static final class Task {
        volatile boolean cancelled;
    }

    private static final long CHUNK = 4L * 1024 * 1024;

    private static final String UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12) gzip";

    private Muxer() { }

    static Task start(final Context ctx, final String videoId, final String quality,
                      final String videoUrl, final String audioUrl,
                      final String name, final long total, final Listener listener) {
        final Task task = new Task();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Muxer.run(task, ctx, videoId, quality, videoUrl, audioUrl, name, total, listener);
            }
        }).start();
        return task;
    }

    private static void run(Task task, Context ctx, String videoId, String quality,
                            String videoUrl, String audioUrl, String name, long total,
                            Listener listener) {
        File vf = null, af = null, mf = null;
        long doneVideo = 0;
        try {
            File dir = tempDir(ctx);
            vf = File.createTempFile("tube-v", ".mp4", dir);
            post(listener, 0, total, "ভিডিও নামছে");
            doneVideo = fetch(task, videoId, quality, true, videoUrl, vf, listener, 0, total);

            long doneAudio = 0;
            if (audioUrl != null && audioUrl.length() > 0) {
                af = File.createTempFile("tube-a", ".m4a", dir);
                post(listener, doneVideo, 0, "অডিও নামছে");
                doneAudio = fetch(task, videoId, quality, false, audioUrl, af, listener, doneVideo, total);
            }
            if (task.cancelled) throw new Exception("বাতিল করা হয়েছে");

            post(listener, doneVideo + doneAudio, total, "জোড়া লাগছে");
            if (af != null) {
                mf = File.createTempFile("tube-m", ".mp4", dir);
                join(vf, af, mf);
            } else {
                mf = vf;
                vf = null;
            }
            if (task.cancelled) throw new Exception("বাতিল করা হয়েছে");

            long bytes = mf.length();
            Uri saved = publish(ctx, mf, name);
            if (task.cancelled) throw new Exception("বাতিল করা হয়েছে");
            finished(listener, saved, name, bytes);
        } catch (Throwable e) {
            failed(listener, shortMessage(e));
        } finally {
            delete(vf);
            delete(af);
            delete(mf);
        }
    }

    /** temp files on shared storage; the app cache can be too small or get trimmed */
    private static File tempDir(Context ctx) {
        try {
            File d = ctx.getExternalFilesDir(null);
            if (d != null) {
                if (!d.exists()) d.mkdirs();
                if (d.isDirectory()) return d;
            }
        } catch (Throwable ignored) { }
        return ctx.getCacheDir();
    }

    private static String shortMessage(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) m = e.getClass().getSimpleName();
        if (m.contains("ENOSPC")) m = "স্টোরেজে জায়গা নেই";
        if (m.length() > 100) m = m.substring(0, 100);
        return m;
    }

    private static void delete(File f) {
        try { if (f != null) f.delete(); } catch (Exception ignored) { }
    }

    private static void post(final Listener l, final long done, final long total,
                             final String stage) {
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() { l.progress(done, total, stage); }
        });
    }

    private static void finished(final Listener l, final Uri uri, final String name,
                                 final long bytes) {
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() { l.done(uri == null ? "" : uri.toString(), name, bytes); }
        });
    }

    private static void failed(final Listener l, final String why) {
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() { l.fail(why); }
        });
    }

    /**
     * Downloads one track piece by piece. The links answer 403 to a plain
     * request, so every piece asks for a bounded byte range (the first 4 MB,
     * then the next, and so on). A refused piece is retried with a freshly
     * resolved link for the same quality, and reading carries on from the
     * current position.
     */
    private static long fetch(Task task, String videoId, String quality, boolean video,
                              String url, File out, Listener listener, long already, long total)
            throws Exception {
        long pos = 0;
        int tries = 0;
        OutputStream os = new FileOutputStream(out);
        try {
            while (true) {
                if (task.cancelled) throw new Exception("বাতিল করা হয়েছে");
                HttpURLConnection cn = null;
                long got = 0;
                long rangeTotal = 0;
                int code = -1;
                try {
                    cn = (HttpURLConnection) new URL(url).openConnection();
                    cn.setInstanceFollowRedirects(true);
                    cn.setConnectTimeout(15000);
                    cn.setReadTimeout(25000);
                    cn.setRequestProperty("User-Agent", UA);
                    cn.setRequestProperty("Range", "bytes=" + pos + "-" + (pos + CHUNK - 1));
                    code = cn.getResponseCode();
                    if (code == 401 || code == 403 || code == 410 || code >= 500) {
                        if (tries++ >= 3) throw new Exception("HTTP " + code);
                        url = freshLink(videoId, quality, video, url);
                        Thread.sleep(500L * tries);
                        continue;
                    }
                    if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
                    rangeTotal = totalOf(cn.getHeaderField("Content-Range"));
                    // once the server says how big this track is, the total is exact
                    long known = rangeTotal > 0 ? already + rangeTotal : total;
                    InputStream in = cn.getInputStream();
                    try {
                        byte[] buf = new byte[65536];
                        int read;
                        long last = 0;
                        while ((read = in.read(buf)) > 0) {
                            if (task.cancelled) throw new Exception("বাতিল করা হয়েছে");
                            os.write(buf, 0, read);
                            got += read;
                            if (got - last > 512 * 1024) {
                                last = got;
                                post(listener, already + pos + got, known,
                                        video ? "ভিডিও নামছে" : "অডিও নামছে");
                            }
                        }
                    } finally {
                        try { in.close(); } catch (Exception ignored) { }
                    }
                    if (got <= 0) {
                        if (tries++ >= 3) throw new Exception("no data");
                        url = freshLink(videoId, quality, video, url);
                        Thread.sleep(500L * tries);
                        continue;
                    }
                    pos += got;
                    tries = 0;
                    if (code != 206 || rangeTotal <= 0) break;
                    if (pos >= rangeTotal) break;
                } catch (java.io.IOException io) {
                    // the connection dropped mid file: keep what arrived and ask again
                    if (code != 206 || tries++ >= 4) throw io;
                    Thread.sleep(700L * tries);
                    if (got > 0) {
                        pos += got;
                        tries = 0;
                    }
                } finally {
                    if (cn != null) cn.disconnect();
                }
            }
        } finally {
            try { os.close(); } catch (Exception ignored) { }
        }
        return pos;
    }

    /** total size from a "bytes 0-99/1000" header */
    private static long totalOf(String contentRange) {
        if (contentRange == null) return 0;
        int slash = contentRange.indexOf('/');
        if (slash < 0) return 0;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** a fresh link for the same quality, or the old one when nothing better is found */
    private static String freshLink(String id, String quality, boolean video, String old) {
        if (id == null || id.length() != 11) return old;
        try {
            for (String[] r : Tube.resolve(id)) {
                if (quality != null && quality.length() > 0 && !quality.equals(r[1])) continue;
                String u = video ? r[0] : (r[3] == null ? "" : r[3]);
                if (u != null && u.length() > 0) return u;
            }
        } catch (Exception ignored) { }
        return old;
    }

    /** puts picture and sound into one mp4 */
    private static void join(File video, File audio, File out) throws Exception {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vTrack = track(ve, "video/");
            int aTrack = track(ae, "audio/");
            if (vTrack < 0) throw new Exception("video track");
            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = mux.addTrack(ve.getTrackFormat(vTrack));
            int aOut = aTrack >= 0 ? mux.addTrack(ae.getTrackFormat(aTrack)) : -1;
            mux.start();
            copy(ve, vTrack, mux, vOut);
            if (aOut >= 0) copy(ae, aTrack, mux, aOut);
            mux.stop();
        } finally {
            try { if (mux != null) mux.release(); } catch (Exception ignored) { }
            try { ve.release(); } catch (Exception ignored) { }
            try { ae.release(); } catch (Exception ignored) { }
        }
    }

    private static int track(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copy(MediaExtractor ex, int track, MediaMuxer mux, int outTrack) {
        ex.selectTrack(track);
        ByteBuffer buf = ByteBuffer.allocateDirect(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    /** saves to the public Downloads folder; a name already in use gets a number */
    private static Uri publish(Context ctx, File src, String name) throws Exception {
        Uri uri = null;
        File dest = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put("_display_name", name);
            cv.put("mime_type", "video/mp4");
            cv.put("relative_path", Environment.DIRECTORY_DOWNLOADS);
            cv.put("is_pending", 1);
            ContentResolver cr = ctx.getContentResolver();
            uri = cr.insert(Uri.parse("content://media/external/downloads"), cv);
            if (uri != null) {
                OutputStream os = cr.openOutputStream(uri);
                copyFile(src, os);
                ContentValues done = new ContentValues();
                done.put("is_pending", 0);
                cr.update(uri, done, null, null);
                return uri;
            }
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        dest = new File(dir, name);
        int n = 2;
        while (dest.exists()) {
            int dot = name.lastIndexOf('.');
            String next = dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                    : name + " (" + n + ")";
            dest = new File(dir, next);
            n++;
        }
        copyFile(src, new FileOutputStream(dest));
        return Uri.fromFile(dest);
    }

    private static void copyFile(File src, OutputStream out) throws Exception {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }

    /** "12.4 MB" and friends, shared with the UI */
    static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return (kb < 10 ? String.format(Locale.US, "%.1f", kb)
                : String.valueOf(Math.round(kb))) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return (mb < 10 ? String.format(Locale.US, "%.1f", mb)
                : String.valueOf(Math.round(mb))) + " MB";
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
