package com.skyble.tube;

import android.content.Context;
import android.content.SharedPreferences;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonStringWriter;
import com.grack.nanojson.JsonWriter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Download job list, kept in memory and mirrored to SharedPreferences as one
 * JSON array. The list is read from disk once per instance; every change
 * rewrites the whole array.
 */
final class Store {

    static final String QUEUED = "queued";
    static final String RUNNING = "running";
    static final String DONE = "done";
    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";

    private static final String PREFS = "jobs";
    private static final String KEY = "jobs";
    private static final int MAX_JOBS = 200;
    private static final int MAX_NAME = 60;
    private static final String FALLBACK_NAME = "skyble-video";

    static final class Job {
        String id;
        String title;
        String quality;
        String name;
        String state;
        String videoId;
        String videoUrl;
        String audioUrl;
        long dmId;
        String uri;
        long done;
        long total;
        int attempt;
        long created;
        long finished;
        String message;
    }

    private final SharedPreferences prefs;
    private final List<Job> jobs = new ArrayList<Job>();
    private boolean loaded;
    private int seq;

    Store(Context ctx) {
        Context app = null;
        try {
            app = ctx == null ? null : ctx.getApplicationContext();
        } catch (Throwable ignored) {
            app = null;
        }
        if (app == null) {
            app = ctx;
        }
        SharedPreferences p = null;
        try {
            if (app != null) {
                p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        } catch (Throwable ignored) {
            p = null;
        }
        this.prefs = p;
    }

    synchronized List<Job> all() {
        load();
        return new ArrayList<Job>(jobs);
    }

    synchronized Job byId(String id) {
        load();
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            if (id.equals(j.id)) {
                return j;
            }
        }
        return null;
    }

    synchronized List<Job> byState(String... states) {
        load();
        List<Job> out = new ArrayList<Job>();
        if (states == null || states.length == 0) {
            out.addAll(jobs);
            return out;
        }
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            for (int s = 0; s < states.length; s++) {
                if (states[s] != null && states[s].equals(j.state)) {
                    out.add(j);
                    break;
                }
            }
        }
        return out;
    }

    synchronized Job add(Job job) {
        load();
        if (job == null) {
            return null;
        }
        if (job.id == null || job.id.isEmpty()) {
            job.id = newId();
        }
        if (job.created <= 0) {
            job.created = System.currentTimeMillis();
        }
        if (job.state == null || job.state.isEmpty()) {
            job.state = QUEUED;
        }
        int at = indexOf(job.id);
        if (at >= 0) {
            jobs.set(at, job);
        } else {
            jobs.add(job);
        }
        tidy();
        persist();
        return job;
    }

    synchronized void save(Job job) {
        load();
        if (job == null) {
            return;
        }
        if (job.id == null || job.id.isEmpty()) {
            add(job);
            return;
        }
        int at = indexOf(job.id);
        if (at >= 0) {
            jobs.set(at, job);
        } else {
            jobs.add(job);
        }
        tidy();
        persist();
    }

    synchronized void remove(String id) {
        load();
        if (id == null) {
            return;
        }
        int at = indexOf(id);
        if (at < 0) {
            return;
        }
        jobs.remove(at);
        persist();
    }

    synchronized void clearDone() {
        load();
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; i--) {
            String st = jobs.get(i).state;
            if (DONE.equals(st) || CANCELLED.equals(st)) {
                jobs.remove(i);
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    synchronized int count(String state) {
        load();
        int n = 0;
        for (int i = 0; i < jobs.size(); i++) {
            if (state == null ? jobs.get(i).state == null : state.equals(jobs.get(i).state)) {
                n++;
            }
        }
        return n;
    }

    synchronized boolean hasActive() {
        load();
        for (int i = 0; i < jobs.size(); i++) {
            String st = jobs.get(i).state;
            if (QUEUED.equals(st) || RUNNING.equals(st)) {
                return true;
            }
        }
        return false;
    }

    // ---- disk ----

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        jobs.clear();
        String raw = null;
        try {
            raw = prefs == null ? null : prefs.getString(KEY, "");
        } catch (Throwable ignored) {
            raw = null;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            JsonArray arr = JsonParser.array().from(raw);
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (item instanceof JsonObject) {
                    jobs.add(read((JsonObject) item));
                }
            }
        } catch (Throwable t) {
            jobs.clear();
        }
        tidy();
    }

    private void persist() {
        if (prefs == null) {
            return;
        }
        try {
            String json = write();
            prefs.edit().putString(KEY, json).apply();
        } catch (Throwable ignored) {
            // keep the in-memory list; disk write is best effort
        }
    }

    private String write() {
        JsonStringWriter w = JsonWriter.string();
        w.array();
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            w.object();
            w.value("id", nz(j.id));
            w.value("title", nz(j.title));
            w.value("quality", nz(j.quality));
            w.value("name", nz(j.name));
            w.value("state", nz(j.state));
            w.value("videoId", nz(j.videoId));
            w.value("videoUrl", nz(j.videoUrl));
            w.value("audioUrl", nz(j.audioUrl));
            w.value("dmId", j.dmId);
            w.value("uri", nz(j.uri));
            w.value("done", j.done);
            w.value("total", j.total);
            w.value("attempt", j.attempt);
            w.value("created", j.created);
            w.value("finished", j.finished);
            w.value("message", nz(j.message));
            w.end();
        }
        w.end();
        return w.done();
    }

    private static Job read(JsonObject o) {
        Job j = new Job();
        j.id = str(o, "id");
        j.title = str(o, "title");
        j.quality = str(o, "quality");
        j.name = str(o, "name");
        j.state = str(o, "state");
        j.videoId = str(o, "videoId");
        j.videoUrl = str(o, "videoUrl");
        j.audioUrl = str(o, "audioUrl");
        j.dmId = num(o, "dmId");
        j.uri = str(o, "uri");
        j.done = num(o, "done");
        j.total = num(o, "total");
        j.attempt = (int) num(o, "attempt");
        j.created = num(o, "created");
        j.finished = num(o, "finished");
        j.message = str(o, "message");
        return j;
    }

    private static String str(JsonObject o, String key) {
        Object v = o.get(key);
        if (v instanceof String) {
            return (String) v;
        }
        return "";
    }

    private static long num(JsonObject o, String key) {
        Object v = o.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return 0L;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---- memory ----

    private int indexOf(String id) {
        for (int i = 0; i < jobs.size(); i++) {
            if (id.equals(jobs.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private String newId() {
        long now = System.currentTimeMillis();
        String id;
        do {
            seq++;
            id = now + "-" + seq;
        } while (indexOf(id) >= 0);
        return id;
    }

    private void tidy() {
        Collections.sort(jobs, new Comparator<Job>() {
            @Override
            public int compare(Job a, Job b) {
                long x = a.created;
                long y = b.created;
                return x == y ? 0 : (x > y ? -1 : 1);
            }
        });
        // list is newest first, so the last finished job is the oldest one
        while (jobs.size() > MAX_JOBS) {
            int drop = -1;
            for (int i = jobs.size() - 1; i >= 0; i--) {
                String st = jobs.get(i).state;
                if (DONE.equals(st) || CANCELLED.equals(st)) {
                    drop = i;
                    break;
                }
            }
            if (drop < 0) {
                break;
            }
            jobs.remove(drop);
        }
    }

    // ---- text ----

    static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            if (kb < 10.0) {
                return String.format(Locale.US, "%.1f KB", kb);
            }
            long whole = Math.round(kb);
            if (whole < 1024) {
                return whole + " KB";
            }
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024.0) {
            if (mb < 10.0) {
                return String.format(Locale.US, "%.1f MB", mb);
            }
            long whole = Math.round(mb);
            if (whole < 1024) {
                return whole + " MB";
            }
        }
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.US, "%.2f GB", gb);
    }

    static String whenText(long millis) {
        if (millis <= 0) {
            return "";
        }
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        String clock = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
        if (sameDay(then, now)) {
            return "Today " + clock;
        }
        Calendar yday = Calendar.getInstance();
        yday.setTimeInMillis(now.getTimeInMillis());
        yday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, yday)) {
            return "Yesterday " + clock;
        }
        return new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(millis));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    static String cleanName(String title) {
        if (title == null) {
            return FALLBACK_NAME;
        }
        StringBuilder sb = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < ' ') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_NAME) {
            int cut = MAX_NAME;
            if (Character.isHighSurrogate(s.charAt(cut - 1))) {
                cut--;
            }
            s = s.substring(0, cut).trim();
        }
        return s.isEmpty() ? FALLBACK_NAME : s;
    }
}
