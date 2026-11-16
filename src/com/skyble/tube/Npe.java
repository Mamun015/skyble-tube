package com.skyble.tube;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves YouTube videos with the bundled NewPipeExtractor.
 *
 * <p>Rows use the same shape as {@link Tube}: {url, quality, sizeText, audioUrl}.
 * An empty audioUrl means the file already holds picture and sound; a non-empty
 * one means the picture and that audio track have to be muxed afterwards.</p>
 */
final class Npe {

    private Npe() { }

    /** mobile Chrome, the same browser string the rest of the app sends */
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;

    /** last title seen by the extractor, used for the file name */
    static volatile String lastTitle = "";

    /** why the last attempt failed, for the log only */
    static volatile String lastError = "";

    // ------------------------------------------------------------ downloader

    /**
     * NewPipe loads player responses, player JavaScript and the visitor data through
     * this. GET and POST are both used by the InnerTube calls, HEAD by the parser.
     */
    static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(final Request request) throws IOException, ReCaptchaException {
            final String method = request.httpMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
                throw new UnsupportedOperationException("method not supported: " + method);
            }
            HttpURLConnection cn = null;
            try {
                cn = (HttpURLConnection) new URL(request.url()).openConnection();
                cn.setRequestMethod(method);
                cn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                cn.setReadTimeout(READ_TIMEOUT_MS);
                cn.setInstanceFollowRedirects(true);
                cn.setRequestProperty("User-Agent", UA);
                cn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                final Map<String, List<String>> headers = request.headers();
                if (headers != null) {
                    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                        final List<String> values = e.getValue();
                        if (values == null || values.isEmpty()) continue;
                        cn.setRequestProperty(e.getKey(), values.get(0));
                    }
                }
                final byte[] data = request.dataToSend();
                if (data != null && data.length > 0) {
                    cn.setDoOutput(true);
                    final java.io.OutputStream out = cn.getOutputStream();
                    try {
                        out.write(data);
                    } finally {
                        out.close();
                    }
                }
                final int code = cn.getResponseCode();
                final String message = cn.getResponseMessage();
                if (code == 429) {
                    throw new ReCaptchaException("reCaptcha challenge requested", request.url());
                }
                InputStream in = null;
                try {
                    in = code >= 400 ? cn.getErrorStream() : cn.getInputStream();
                } catch (IOException ignored) { }
                final byte[] body = in == null ? new byte[0] : readAll(in);
                final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : cn.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        responseHeaders.put(e.getKey(), e.getValue());
                    }
                }
                return new Response(code, message, responseHeaders,
                        new String(body, "UTF-8"), cn.getURL().toString());
            } finally {
                if (cn != null) cn.disconnect();
            }
        }

        private static byte[] readAll(final InputStream in) throws IOException {
            try {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------ bootstrap

    private static volatile boolean ready;

    /** must run on a background thread: it installs the downloader and loads classes */
    private static synchronized boolean ensureReady() {
        if (ready) return true;
        try {
            NewPipe.init(new HttpDownloader());
            ready = true;
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = describe(t);
            return false;
        }
    }

    // ------------------------------------------------------------ resolver

    /**
     * Rows {url, quality, sizeText, audioUrl}, best quality first, at most four.
     * Never throws: an empty list means "use the old resolver".
     */
    static List<String[]> resolve(final String videoId) {
        final List<String[]> rows = new ArrayList<>();
        lastTitle = "";
        lastError = "";
        if (videoId == null || videoId.length() != 11) return rows;
        if (!ensureReady()) return rows;

        YoutubeStreamExtractor extractor = null;
        try {
            extractor = (YoutubeStreamExtractor) ServiceList.YouTube
                    .getStreamExtractor("https://www.youtube.com/watch?v=" + videoId);
            extractor.fetchPage();
            final String name = extractor.getName();
            if (name != null) lastTitle = name;
            fillRows(extractor, rows);
        } catch (Throwable t) {
            lastError = describe(t);
            rows.clear();
        }
        return rows;
    }

    private static void fillRows(final YoutubeStreamExtractor extractor, final List<String[]> out) {
        // 1. pictures and sound in one file
        final List<Stream> muxed = safeVideoStreams(extractor, false);
        for (Stream s : muxed) {
            final String url = content(s);
            if (url.length() == 0) continue;
            if (s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (s.getFormat() != MediaFormat.MPEG_4) continue;
            final String quality = qualityOf(s);
            out.add(new String[]{url, quality, sizeText(size(s)), ""});
        }

        // 2. the two best 720p/1080p video tracks paired with the best m4a audio
        final Stream audio = bestM4a(extractor);
        if (audio != null) {
            final String audioUrl = content(audio);
            if (audioUrl.length() > 0) {
                final long audioSize = size(audio);
                final List<Stream> picked = bestVideoOnly(extractor);
                for (Stream v : picked) {
                    final String url = content(v);
                    if (url.length() == 0) continue;
                    final long total = size(v) > 0 && audioSize > 0 ? size(v) + audioSize : 0;
                    out.add(new String[]{url, qualityOf(v), sizeText(total), audioUrl});
                }
            }
        }

        sortAndTrim(out);
    }

    /** the two best video-only MPEG-4 tracks at 720p/1080p, avc1 preferred over av01/vp9 */
    private static List<Stream> bestVideoOnly(final YoutubeStreamExtractor extractor) {
        final Map<Integer, Stream> perHeight = new LinkedHashMap<>();
        for (Stream s : safeVideoStreams(extractor, true)) {
            if (s.getFormat() != MediaFormat.MPEG_4) continue;
            if (content(s).length() == 0) continue;
            final int h = height(s);
            if (h != 720 && h != 1080) continue;
            final Stream current = perHeight.get(h);
            if (current == null || (avc1(s) && !avc1(current))) perHeight.put(h, s);
        }
        final List<Integer> heights = new ArrayList<>(perHeight.keySet());
        heights.sort((a, b) -> b - a);
        final List<Stream> picked = new ArrayList<>();
        for (Integer h : heights) {
            picked.add(perHeight.get(h));
            if (picked.size() >= 2) break;
        }
        return picked;
    }

    /** avc1 plays everywhere; av01 and vp9 are only used when nothing else is offered */
    private static boolean avc1(final Stream s) {
        final String codec = codecOf(s);
        return codec != null && codec.toLowerCase(Locale.US).startsWith("avc1");
    }

    private static String codecOf(final Stream s) {
        if (s instanceof VideoStream) {
            final VideoStream v = (VideoStream) s;
            if (v.getCodec() != null) return v.getCodec();
            if (v.getItagItem() != null && v.getItagItem().getCodec() != null) {
                return v.getItagItem().getCodec();
            }
        }
        return null;
    }

    /** best audio track in an mp4 container (itag 140 and friends) */
    private static Stream bestM4a(final YoutubeStreamExtractor extractor) {
        Stream best = null;
        long bestBitrate = -1;
        try {
            final List<AudioStream> audio = extractor.getAudioStreams();
            if (audio == null) return null;
            for (AudioStream a : audio) {
                if (a.getFormat() != MediaFormat.M4A) continue;
                if (content(a).length() == 0) continue;
                long bitrate = a.getAverageBitrate() > 0 ? a.getAverageBitrate() : a.getBitrate();
                if (best == null || bitrate > bestBitrate) {
                    best = a;
                    bestBitrate = bitrate;
                }
            }
        } catch (Throwable t) {
            lastError = describe(t);
        }
        return best;
    }

    private static List<Stream> safeVideoStreams(final YoutubeStreamExtractor extractor,
                                                 final boolean onlyVideoOnly) {
        final List<Stream> list = new ArrayList<>();
        try {
            final List<VideoStream> found = onlyVideoOnly
                    ? extractor.getVideoOnlyStreams()
                    : extractor.getVideoStreams();
            if (found != null) list.addAll(found);
        } catch (Throwable t) {
            lastError = describe(t);
        }
        return list;
    }

    private static String content(final Stream s) {
        final String c = s.getContent();
        return c == null ? "" : c;
    }

    private static long size(final Stream s) {
        if (s.getItagItem() == null) return 0;
        return Math.max(0, s.getItagItem().getContentLength());
    }

    private static int height(final Stream s) {
        if (s instanceof VideoStream) {
            final int h = ((VideoStream) s).getHeight();
            if (h > 0) return h;
        }
        return rank(qualityOf(s));
    }

    private static String qualityOf(final Stream s) {
        String q = "";
        if (s instanceof VideoStream) q = ((VideoStream) s).getResolution();
        if (q == null || q.trim().length() == 0) {
            final int h = s instanceof VideoStream ? ((VideoStream) s).getHeight() : 0;
            q = h > 0 ? h + "p" : "Video";
        }
        return q.trim();
    }

    /** best quality first, no two rows with the same label, four rows at most */
    private static void sortAndTrim(final List<String[]> rows) {
        rows.sort((a, b) -> rank(b[1]) - rank(a[1]));
        final List<String[]> kept = new ArrayList<>();
        for (String[] row : rows) {
            boolean duplicate = false;
            for (String[] k : kept) {
                if (k[1].equals(row[1])) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            kept.add(row);
            if (kept.size() >= 4) break;
        }
        rows.clear();
        rows.addAll(kept);
    }

    private static int rank(final String q) {
        if (q == null) return 0;
        final String digits = q.replaceAll("[^0-9]", "");
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.length() > 4 ? digits.substring(0, 4) : digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String sizeText(final long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String describe(final Throwable t) {
        if (t == null) return "";
        final String m = t.getMessage();
        final String name = t.getClass().getSimpleName();
        final String text = m == null || m.length() == 0 ? name : name + ": " + m;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
