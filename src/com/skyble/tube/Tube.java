package com.skyble.tube;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds downloadable links for a YouTube video.
 *
 * The watch page gives nothing usable, so two paths are tried in order:
 * 1. YouTube's own player API through the Android VR client, which hands back
 *    direct googlevideo links (fast, no third party in the middle).
 * 2. Public Piped and Invidious front-ends for the times YouTube refuses.
 */
final class Tube {

    private Tube() { }

    private static final String KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";

    /**
     * YouTube only serves plain file links to its own apps, so the player API is
     * called with phone app clients, one after another until one answers.
     */
    private static final String ANDROID_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    private static final String VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; "
                    + "Quest 3 Build/SQ3A.220605.009.A1) gzip";
    private static final String TS_UA =
            "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip";

    // আগে এগুলো; কাজ না করলে সরাসরি ইনস্ট্যান্স তালিকা থেকেও খোঁজা হয়
    private static final String[] HOSTS = {
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.drgns.space"
    };

    private static final String[] INV_HOSTS = {
            "https://inv.nadeko.net",
            "https://invidious.f5.si",
            "https://yewtu.be"
    };

    private static final String INV_LIST = "https://api.invidious.io/instances.json?sort_by=health";

    private static final Pattern V_PARAM = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern SHORTS = Pattern.compile("/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})");
    private static final Pattern BARE = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** শেষবার কেন আটকাল, সেটা পপআপে দেখানো হয় */
    static String lastError = "";

    /** শেষ খোঁজায় পাওয়া ভিডিওর নাম, ফাইলের নামে কাজে লাগে */
    static String lastTitle = "";

    /** কেন আটকাল, সেটা সহজ ভাষায় */
    static String friendlyError() {
        String e = lastError == null ? "" : lastError.toLowerCase(Locale.US);
        if (e.contains("bot") || e.contains("sign in")) {
            return "YouTube asked for a robot check. Try again later or on another network.";
        }
        if (e.length() == 0) return "";
        return "YouTube will not hand this video to another app. That happens with some music and licensed videos.";
    }

    /** পেজটা ইউটিউবের কি না */
    static boolean isYouTube(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("youtube.com") || u.contains("youtu.be")
                || u.contains("youtube-nocookie.com");
    }

    /** ভিডিওর আইডি — ইউটিউব লিংক না হলে null */
    static String videoId(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!isYouTube(u)) return null;
        Matcher m = V_PARAM.matcher(u);
        if (m.find()) return m.group(1);
        m = SHORTS.matcher(u);
        if (m.find()) return m.group(1);
        int cut = u.indexOf('?');
        if (cut > 0) u = u.substring(0, cut);
        cut = u.indexOf('#');
        if (cut > 0) u = u.substring(0, cut);
        String tail = u.substring(u.lastIndexOf('/') + 1);
        if (BARE.matcher(tail).matches()) return tail;
        return null;
    }

    /**
     * সারি: {url, quality, sizeText, audioUrl}. audioUrl খালি মানে ফাইলটা
     * একটাই (ছবি+আওয়াজ একসাথে); না খালি হলে ওই দুটো জোড়া লাগাতে হবে।
     * ভালো মান আগে।
     */
    static List<String[]> resolve(String id) {
        List<String[]> out = new ArrayList<>();
        lastError = "";
        if (id == null || id.length() != 11) return out;
        long deadline = System.currentTimeMillis() + 12000;

        try {
            if (innertube(id, out)) return out;
        } catch (Exception ignored) { }

        for (String host : HOSTS) {
            if (System.currentTimeMillis() > deadline) return out;
            if (tryPiped(host, id, out)) return out;
        }
        for (String host : INV_HOSTS) {
            if (System.currentTimeMillis() > deadline) return out;
            if (tryInvidious(host, id, out)) return out;
        }
        for (String host : invidiousFromList()) {
            if (System.currentTimeMillis() > deadline) return out;
            if (tryInvidious(host, id, out)) return out;
        }
        return out;
    }

    // ------------------------------------------------------------ YouTube

    private static String androidClient(String visitor) {
        return "{\"clientName\":\"ANDROID\",\"clientVersion\":\"20.10.38\","
                + "\"androidSdkVersion\":34,\"osName\":\"Android\",\"osVersion\":\"14\","
                + "\"hl\":\"en\",\"gl\":\"US\",\"utcOffsetMinutes\":0"
                + (visitor == null ? "" : ",\"visitorData\":\"" + visitor + "\"") + "}";
    }

    private static String vrClient() {
        return "{\"clientName\":\"ANDROID_VR\",\"clientVersion\":\"1.60.19\","
                + "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\","
                + "\"osName\":\"Android\",\"osVersion\":\"12\",\"androidSdkVersion\":32,"
                + "\"hl\":\"en\",\"gl\":\"US\",\"utcOffsetMinutes\":0}";
    }

    private static String testsuiteClient(String visitor) {
        return "{\"clientName\":\"ANDROID_TESTSUITE\",\"clientVersion\":\"1.9\","
                + "\"androidSdkVersion\":30,\"osName\":\"Android\",\"osVersion\":\"11\","
                + "\"hl\":\"en\",\"gl\":\"US\""
                + (visitor == null ? "" : ",\"visitorData\":\"" + visitor + "\"") + "}";
    }

    private static boolean innertube(String id, List<String[]> out) {
        String vd = visitorData();
        String[][] clients = {
                {androidClient(vd), "3", "20.10.38", ANDROID_UA},
                {vrClient(), "28", "1.60.19", VR_UA},
                {testsuiteClient(vd), "30", "1.9", TS_UA}
        };
        for (String[] c : clients) {
            List<String[]> got = player(id, c[0], c[1], c[2], c[3]);
            if (got.isEmpty()) continue;
            out.addAll(got);
            return true;
        }
        return false;
    }

    /**
     * একটা ক্লায়েন্ট দিয়েই তালিকা: ০ = ফোনের অ্যাপ, ১ = VR, ২ = টেস্ট সুইট।
     * ডাউনলোড আটকালে অন্য ক্লায়েন্টের লিংক দিয়ে চেষ্টা করা হয়।
     */
    static List<String[]> resolveClient(String id, int which) {
        List<String[]> out = new ArrayList<>();
        if (id == null || id.length() != 11) return out;
        String vd = visitorData();
        String[][] clients = {
                {androidClient(vd), "3", "20.10.38", ANDROID_UA},
                {vrClient(), "28", "1.60.19", VR_UA},
                {testsuiteClient(vd), "30", "1.9", TS_UA}
        };
        String[] c = clients[Math.max(0, Math.min(clients.length - 1, which))];
        try {
            out.addAll(player(id, c[0], c[1], c[2], c[3]));
        } catch (Exception ignored) { }
        return out;
    }

    private static String VISITOR;

    /** একবারই আনা হয়; ইউটিউব এটা দিয়ে নতুন ব্যবহারকারী ধরে নেয় */
    private static String visitorData() {
        if (VISITOR != null) return VISITOR.length() == 0 ? null : VISITOR;
        VISITOR = "";
        String resp = post("https://www.youtube.com/youtubei/v1/visitor_id?key=" + KEY,
                "{\"context\":{\"client\":" + androidClient(null) + "}}",
                ANDROID_UA, "3", "20.10.38");
        if (resp == null) return null;
        try {
            JSONObject o = new JSONObject(resp);
            JSONObject ctx = o.optJSONObject("responseContext");
            String vd = ctx == null ? "" : ctx.optString("visitorData", "");
            if (vd.length() == 0) vd = o.optString("visitorData", "");
            if (vd.length() > 0) VISITOR = vd;
        } catch (Exception ignored) { }
        return VISITOR.length() == 0 ? null : VISITOR;
    }

    /** এক ক্লায়েন্ট দিয়ে প্লেয়ার API; যা পাওয়া গেল তার তালিকা ফেরায় */
    private static List<String[]> player(String id, String clientJson, String clientName,
                                         String clientVersion, String ua) {
        List<String[]> found = new ArrayList<>();
        String body = "{\"context\":{\"client\":" + clientJson
                + ",\"user\":{\"lockedSafetyMode\":false},\"request\":{\"useSsl\":true}},"
                + "\"videoId\":\"" + id + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
        String resp = post("https://www.youtube.com/youtubei/v1/player?key=" + KEY, body,
                ua, clientName, clientVersion);
        if (resp == null) return found;
        try {
            JSONObject o = new JSONObject(resp);
            JSONObject details = o.optJSONObject("videoDetails");
            if (details != null) {
                String title = details.optString("title", "");
                if (title.length() > 0) lastTitle = title;
            }
            JSONObject status = o.optJSONObject("playabilityStatus");
            if (status != null && !"OK".equals(status.optString("status"))) {
                String reason = status.optString("reason", "");
                if (reason.length() > 0) lastError = reason;
                return found;
            }
            JSONObject sd = o.optJSONObject("streamingData");
            if (sd == null) return found;

            String audio = null;
            long audioLen = 0;
            JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
            if (adaptive != null) {
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f = adaptive.optJSONObject(i);
                    if (f == null) continue;
                    String mime = f.optString("mimeType", "");
                    if (!mime.startsWith("audio/mp4")) continue;
                    String u = f.optString("url", "");
                    if (u.length() == 0) continue;
                    long len = f.optLong("contentLength", 0);
                    if (audio == null || f.optInt("itag", 0) == 140) {
                        audio = u;
                        audioLen = len;
                        if (f.optInt("itag", 0) == 140) break;
                    }
                }
            }

            // ছবি+আওয়াজ একসাথে: সাধারণত ৩৬০p, একটাই ফাইল
            JSONArray fm = sd.optJSONArray("formats");
            if (fm != null) {
                for (int i = 0; i < fm.length(); i++) {
                    JSONObject f = fm.optJSONObject(i);
                    if (f == null) continue;
                    String u = f.optString("url", "");
                    if (u.length() == 0) continue;
                    if (!f.optString("mimeType", "").startsWith("video/mp4")) continue;
                    String q = f.optString("qualityLabel", "360p");
                    found.add(new String[]{u, cleanQuality(q),
                            sizeText(f.optLong("contentLength", 0)), ""});
                }
            }

            // ৭২০p/১০৮০p: ছবি আর আওয়াজ আলাদা, পরে জোড়া লাগাতে হয়
            if (adaptive != null && audio != null) {
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f = adaptive.optJSONObject(i);
                    if (f == null) continue;
                    String mime = f.optString("mimeType", "");
                    if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue;
                    int h = f.optInt("height", 0);
                    String label = f.optString("qualityLabel", "");
                    // উচ্চতা আর লেবেলের সংখ্যা সবসময় মেলে না (480p = 450), তাই লেবেল দেখে
                    if (rank(label) > 0 ? rank(label) < 480 : h < 480) continue;
                    String u = f.optString("url", "");
                    if (u.length() == 0) continue;
                    long len = f.optLong("contentLength", 0);
                    if (label.length() == 0) label = h + "p";
                    found.add(new String[]{u, cleanQuality(label), sizeText(len + audioLen), audio});
                }
            }

            if (found.isEmpty()) return found;
            found.sort((a, b) -> rank(b[1]) - rank(a[1]));
            List<String[]> trimmed = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (String[] f : found) {
                if (seen.contains(f[1])) continue;
                seen.add(f[1]);
                trimmed.add(f);
                if (trimmed.size() >= 4) break;
            }
            return trimmed;
        } catch (Exception e) {
            return found;
        }
    }

    private static String post(String url, String body, String ua, String clientName,
                               String clientVersion) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(3500);
            cn.setReadTimeout(5000);
            cn.setRequestMethod("POST");
            cn.setDoOutput(true);
            cn.setInstanceFollowRedirects(true);
            cn.setRequestProperty("Content-Type", "application/json");
            cn.setRequestProperty("User-Agent", ua);
            cn.setRequestProperty("X-YouTube-Client-Name", clientName);
            cn.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            OutputStream os = cn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = cn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(cn.getInputStream(), "UTF-8"));
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 8000000) break;
            }
            r.close();
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }

    // ------------------------------------------------------------ front-ends

    private static boolean tryPiped(String host, String id, List<String[]> out) {
        String body = get(host + "/streams/" + URLEncoder.encode(id));
        return body != null && addPiped(body, out);
    }

    private static boolean tryInvidious(String host, String id, List<String[]> out) {
        String body = get(host + "/api/v1/videos/" + URLEncoder.encode(id)
                + "?fields=title,formatStreams");
        return body != null && addInvidious(body, out);
    }

    private static List<String> invidiousFromList() {
        List<String> hosts = new ArrayList<>();
        try {
            String body = get(INV_LIST);
            if (body == null) return hosts;
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length() && hosts.size() < 4; i++) {
                JSONArray pair = arr.optJSONArray(i);
                if (pair == null || pair.length() < 2) continue;
                JSONObject info = pair.optJSONObject(1);
                if (info == null || !info.optBoolean("api", false)) continue;
                String uri = info.optString("uri", "");
                if (uri.length() > 8) hosts.add(trimSlash(uri));
            }
        } catch (Exception ignored) { }
        return hosts;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Piped: videoOnly=false মানে ছবি+আওয়াজ একসাথে */
    private static boolean addPiped(String body, List<String[]> out) {
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("error") && !o.isNull("error")) return false;
            String pt = o.optString("title", "");
            if (pt.length() > 0) lastTitle = pt;
            JSONArray arr = o.optJSONArray("videoStreams");
            if (arr == null) return false;
            List<String[]> found = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null || s.optBoolean("videoOnly", true)) continue;
                String url = s.optString("url", "");
                String mime = s.optString("mimeType", "");
                if (url.length() == 0 || !mime.startsWith("video/mp4")) continue;
                if (!downloadable(url)) continue;
                String q = s.optString("quality", "");
                if (q.length() == 0) q = s.optString("qualityLabel", "");
                long len = 0;
                try {
                    len = s.optLong("contentLength", 0);
                } catch (Exception ignored) { }
                found.add(new String[]{url, cleanQuality(q), len > 0 ? sizeText(len) : "", ""});
            }
            return finish(found, out);
        } catch (Exception e) {
            return false;
        }
    }

    /** Invidious: formatStreams-এ ছবি+আওয়াজ একসাথে থাকে */
    private static boolean addInvidious(String body, List<String[]> out) {
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("formatStreams");
            if (arr == null) return false;
            List<String[]> found = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String url = s.optString("url", "");
                String type = s.optString("container", s.optString("type", ""));
                if (url.length() == 0 || !downloadable(url)) continue;
                if (type.toLowerCase(Locale.US).contains("webm")) continue;
                String q = s.optString("qualityLabel", s.optString("resolution", ""));
                found.add(new String[]{url, cleanQuality(q), "", ""});
            }
            return finish(found, out);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean finish(List<String[]> found, List<String[]> out) {
        if (found.isEmpty()) return false;
        found.sort((a, b) -> rank(b[1]) - rank(a[1]));
        List<String> seen = new ArrayList<>();
        for (String[] f : found) {
            if (seen.contains(f[1])) continue;
            seen.add(f[1]);
            out.add(f);
            if (out.size() >= 6) break;
        }
        return !out.isEmpty();
    }

    /** যে সার্ভার থেকে সোজা নামানো যায় (গুগলের স্ট্রিম বা ফ্রন্টএন্ডের প্রক্সি) */
    private static boolean downloadable(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.endsWith("googlevideo.com") || host.contains("piped")
                    || host.contains("invidious") || host.endsWith("youtube.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static int rank(String q) {
        if (q == null) return 0;
        String digits = q.replaceAll("[^0-9]", "");
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.length() > 4 ? digits.substring(0, 4) : digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String cleanQuality(String q) {
        if (q == null || q.length() == 0) return "Video";
        String s = q.replace("p60", "p").replace("p30", "p").replace("p25", "p").trim();
        if (s.length() > 14) s = s.substring(0, 14);
        return s;
    }

    static String sizeText(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String get(String url) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(3000);
            cn.setReadTimeout(4000);
            cn.setInstanceFollowRedirects(true);
            cn.setRequestProperty("Accept", "application/json");
            cn.setRequestProperty("User-Agent", "Skyble/1.1");
            int code = cn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(cn.getInputStream(), "UTF-8"));
            String line;
            int guard = 0;
            while ((line = r.readLine()) != null && guard++ < 4000) sb.append(line);
            r.close();
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }
}
