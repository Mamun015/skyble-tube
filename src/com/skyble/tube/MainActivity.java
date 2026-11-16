package com.skyble.tube;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skyble Tube: a small YouTube downloader with its own window. A link arrives
 * from the launcher, a share sheet, a youtube.com link or Skyble Browser; the
 * user picks a quality and the file lands in Downloads. Three tabs: new
 * download, running jobs, finished files.
 */
public class MainActivity extends Activity {

    /** new action; the old plugin action is still understood by the manifest */
    static final String ACTION_DOWNLOAD = "com.skyble.tube.DOWNLOAD";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int REQ_WRITE = 11;
    private static final Pattern LINK = Pattern.compile("https?://\\S+");

    private static final int BG = 0xFFF4F6FA;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF12161F;
    private static final int MUTED = 0xFF6B7280;
    private static final int LINE = 0xFFE4E8F0;
    private static final int BRAND = 0xFFC2172B;
    private static final int BRAND_DEEP = 0xFF7C0C18;
    private static final int GOOD = 0xFF15803D;
    private static final int BAD = 0xFFB3261E;
    private static final int SOFT = 0xFFFBEDEF;

    private static final int TAB_NEW = 0;
    private static final int TAB_RUNNING = 1;
    private static final int TAB_DONE = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private static Store sharedStore;
    private Store store;

    private ScrollView scroll;
    private LinearLayout content;
    private final TextView[] tabs = new TextView[3];

    private EditText linkBox;
    private TextView statusView;
    private LinearLayout resultBox;

    private final List<String[]> found = new ArrayList<>();
    private String statusText = "";
    private boolean statusBad = false;
    private String videoTitle = "";
    private String curVideoId = "";
    private int searchToken = 0;
    private boolean searching = false;

    private int tab = TAB_NEW;

    /** live progress widgets, keyed by job id */
    private final Map<String, Row> rows = new HashMap<String, Row>();
    /** running muxer jobs, keyed by job id */
    private final Map<String, Muxer.Task> tasks = new HashMap<String, Muxer.Task>();
    /** DownloadManager id to job id */
    private final Map<Long, String> dmIndex = new HashMap<Long, String>();
    /** last time a job was written to disk, to keep progress cheap */
    private final Map<String, Long> lastSave = new HashMap<String, Long>();

    private String pendingUrl, pendingQuality, pendingSize, pendingAudio, pendingTitle, pendingId;

    private static final class Row {
        ProgressBar bar;
        TextView pct;
        TextView meta;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            boolean again = refreshRunning();
            if (again) ui.postDelayed(this, 1200);
        }
    };

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (sharedStore == null) sharedStore = new Store(getApplicationContext());
        store = sharedStore;
        setWindow();
        setContentView(buildShell());
        reconcile();
        handleIntent(getIntent());
        selectTab(TAB_NEW, false);
        askWritePermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    private void setWindow() {
        Window w = getWindow();
        int flags = 0;
        try {
            w.setStatusBarColor(BRAND_DEEP);
            if (Build.VERSION.SDK_INT >= 28) {
                w.setNavigationBarColor(0xFFFFFFFF);
                w.setNavigationBarDividerColor(LINE);
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else if (Build.VERSION.SDK_INT >= 26) {
                w.setNavigationBarColor(0xFFFFFFFF);
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                w.setNavigationBarColor(0xFF1B1B1B);
            }
            w.getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    private DownloadManager dm() {
        return (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
    }

    // --------------------------------------------------------------- intents

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String raw = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            raw = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (raw == null) raw = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) raw = intent.getData().toString();
        } else if (intent.hasExtra("url") || intent.hasExtra("link")) {
            raw = intent.getStringExtra("url");
            if (raw == null) raw = intent.getStringExtra("link");
        }
        String text = firstLink(raw);
        if (text == null || text.length() == 0) return;
        selectTab(TAB_NEW, true);
        linkBox.setText(text);
        search();
    }

    private static String firstLink(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m = LINK.matcher(s);
        if (m.find()) return m.group();
        return s;
    }

    // ---------------------------------------------------------------- search

    private void search() {
        final String raw = linkBox == null ? "" : linkBox.getText().toString().trim();
        final String id = Tube.videoId(firstLink(raw));
        if (id == null) {
            setStatus("ইউটিউব লিংক পাওয়া যায়নি", true);
            return;
        }
        searching = true;
        found.clear();
        setStatus("ভিডিও খোঁজা হচ্ছে…", false);
        renderNew();
        final int token = ++searchToken;
        // a slow network must not lock the screen: 25 s and the button comes back
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != searchToken || !searching) return;
                searching = false;
                setStatus("সময় শেষ · আবার চেষ্টা করো", true);
            }
        }, 25000);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String[]> resolved = null;
                String title = "";
                try {
                    resolved = Npe.resolve(id);
                    if (resolved != null && !resolved.isEmpty()) title = Npe.lastTitle;
                } catch (Throwable ignored) {
                    resolved = null;
                }
                if (resolved == null || resolved.isEmpty()) {
                    try {
                        resolved = Tube.resolve(id);
                        title = Tube.lastTitle;
                    } catch (Throwable ignored) {
                        resolved = null;
                    }
                }
                final List<String[]> list = resolved == null ? new ArrayList<String[]>() : resolved;
                final String found_title = title;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != searchToken || isFinishing()) return;
                        searching = false;
                        videoTitle = found_title == null ? "" : found_title;
                        curVideoId = id;
                        if (list.isEmpty()) {
                            String why = Tube.friendlyError();
                            found.clear();
                            setStatus("এই ভিডিওটা এখন নামানো যাচ্ছে না"
                                    + (why.length() == 0 ? "" : " · " + why), true);
                            renderNew();
                            return;
                        }
                        found.addAll(list);
                        setStatus(videoTitle.length() > 0 ? videoTitle : "কোয়ালিটি বেছে নাও", false);
                        renderNew();
                    }
                });
            }
        }).start();
    }

    // -------------------------------------------------------------- download

    private void startDownload(String url, String quality, String size, String audioUrl,
                               String title, String videoId) {
        if (!hasWritePermission()) {
            pendingUrl = url;
            pendingQuality = quality;
            pendingSize = size;
            pendingAudio = audioUrl;
            pendingTitle = title;
            pendingId = videoId;
            setStatus("স্টোরেজের অনুমতি দাও, তারপর আবার চাপো", true);
            renderNew();
            requestWritePermission();
            return;
        }
        String base = Store.cleanName(title);
        Store.Job job = new Store.Job();
        job.title = title == null ? "" : title;
        job.quality = quality == null ? "" : quality;
        job.name = base + ".mp4";
        job.state = Store.QUEUED;
        job.videoId = videoId == null ? "" : videoId;
        job.videoUrl = url;
        job.audioUrl = audioUrl == null ? "" : audioUrl;
        job.total = parseSize(size);
        job.created = System.currentTimeMillis();
        job.message = "";
        store.add(job);

        if (job.audioUrl.length() > 0) {
            startMuxer(job);
        } else {
            enqueue(job, job.name, 0);
        }
        selectTab(TAB_RUNNING, true);
        refreshRunning();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    private void startMuxer(final Store.Job job) {
        job.state = Store.RUNNING;
        job.message = "শুরু হয়েছে";
        store.save(job);
        Muxer.Task task = Muxer.start(this, job.videoId, job.quality, job.videoUrl,
                job.audioUrl, job.name, job.total, new Muxer.Listener() {
                    @Override
                    public void progress(long done, long total, String stage) {
                        Store.Job live = store.byId(job.id);
                        if (live == null || Store.CANCELLED.equals(live.state)) return;
                        live.done = done;
                        if (total > 0) live.total = total;
                        live.message = stage;
                        live.state = Store.RUNNING;
                        persist(live, false);
                        updateRow(live);
                    }

                    @Override
                    public void done(String uri, String name, long bytes) {
                        Store.Job live = store.byId(job.id);
                        tasks.remove(job.id);
                        if (live == null) return;
                        if (Store.CANCELLED.equals(live.state)) return;
                        live.state = Store.DONE;
                        live.uri = uri == null ? "" : uri;
                        live.name = name == null ? live.name : name;
                        live.done = bytes;
                        live.total = bytes;
                        live.finished = System.currentTimeMillis();
                        live.message = "";
                        store.save(live);
                        afterChange(true);
                        toast("ডাউনলোড শেষ · Downloads");
                    }

                    @Override
                    public void fail(String message) {
                        Store.Job live = store.byId(job.id);
                        tasks.remove(job.id);
                        if (live == null) return;
                        if (Store.CANCELLED.equals(live.state)) return;
                        live.state = Store.FAILED;
                        live.message = message == null ? "ব্যর্থ" : message;
                        store.save(live);
                        afterChange(true);
                    }
                });
        tasks.put(job.id, task);
    }

    private void enqueue(final Store.Job job, String name, int attempt) {
        try {
            DownloadManager d = dm();
            if (d == null) {
                job.state = Store.FAILED;
                job.message = "ডাউনলোডার পাওয়া যায়নি";
                store.save(job);
                afterChange(true);
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(job.videoUrl));
            req.setTitle(label(job));
            req.setDescription("Skyble Tube · " + job.quality);
            req.setMimeType("video/mp4");
            req.addRequestHeader("User-Agent", UA);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.allowScanningByMediaScanner();
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            long id = d.enqueue(req);
            job.dmId = id;
            job.attempt = attempt;
            job.name = name;
            job.state = Store.RUNNING;
            job.message = "নামছে";
            store.save(job);
            dmIndex.put(id, job.id);
        } catch (Exception e) {
            job.state = Store.FAILED;
            job.message = shortError(e);
            store.save(job);
            afterChange(true);
        }
    }

    /** brings every DownloadManager job up to date; true while something still runs */
    private boolean refreshRunning() {
        boolean again = false;
        List<Store.Job> list = store.byState(Store.QUEUED, Store.RUNNING);
        for (Store.Job job : list) {
            if (job.dmId <= 0) {
                if (Store.QUEUED.equals(job.state)) {
                    job.state = Store.FAILED;
                    job.message = "শুরু হয়নি";
                    store.save(job);
                    afterChange(true);
                    again = true;
                }
                continue;
            }
            DownloadManager d = dm();
            if (d == null) continue;
            android.database.Cursor c = null;
            try {
                c = d.query(new DownloadManager.Query().setFilterById(job.dmId));
                if (c == null || !c.moveToFirst()) {
                    job.state = Store.FAILED;
                    job.message = "ডাউনলোডার রেকর্ড পাওয়া যায়নি";
                    store.save(job);
                    afterChange(true);
                    again = true;
                    continue;
                }
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    job.state = Store.DONE;
                    job.done = total > 0 ? total : done;
                    job.total = job.done;
                    job.finished = System.currentTimeMillis();
                    job.message = "";
                    try {
                        Uri u = d.getUriForDownloadedFile(job.dmId);
                        job.uri = u == null ? "" : u.toString();
                    } catch (Exception ignored) { }
                    store.save(job);
                    afterChange(true);
                    toast("ডাউনলোড শেষ · Downloads");
                    continue;
                }
                if (st == DownloadManager.STATUS_FAILED) {
                    int reason = c.getInt(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS && job.attempt < 5) {
                        String next = numbered(job.name, job.attempt + 2);
                        long old = job.dmId;
                        dmIndex.remove(old);
                        enqueue(job, next, job.attempt + 1);
                        afterChange(true);
                        again = true;
                        continue;
                    }
                    job.state = Store.FAILED;
                    job.message = reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                            ? "স্টোরেজে জায়গা নেই" : "ডাউনলোড ব্যর্থ";
                    store.save(job);
                    afterChange(true);
                    continue;
                }
                job.state = Store.RUNNING;
                job.done = done;
                if (total > 0) job.total = total;
                job.message = st == DownloadManager.STATUS_PAUSED ? "থেমে আছে" : "নামছে";
                persist(job, false);
                updateRow(job);
                again = true;
            } catch (Exception ignored) {
                again = true;
            } finally {
                if (c != null) try { c.close(); } catch (Exception ignored) { }
            }
        }
        return again;
    }

    private static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
        return name + " (" + n + ")";
    }

    private void cancel(Store.Job job) {
        job.state = Store.CANCELLED;
        job.message = "বাতিল";
        store.save(job);
        if (job.dmId > 0) {
            try {
                DownloadManager d = dm();
                if (d != null) d.remove(job.dmId);
            } catch (Exception ignored) { }
        }
        Muxer.Task t = tasks.remove(job.id);
        if (t != null) t.cancelled = true;
        afterChange(true);
    }

    private void retry(final Store.Job job) {
        final String id = job.videoId;
        final String quality = job.quality;
        final String title = job.title;
        if (id == null || id.length() != 11) {
            toast("লিংকটা আর নেই, আবার লিংক দাও");
            return;
        }
        toast("লিংক আবার খোঁজা হচ্ছে…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String[]> list = null;
                try {
                    list = Npe.resolve(id);
                } catch (Throwable ignored) { }
                if (list == null || list.isEmpty()) {
                    try { list = Tube.resolve(id); } catch (Throwable ignored) { }
                }
                final List<String[]> rowsFound = list;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (rowsFound == null || rowsFound.isEmpty()) {
                            setStatus("লিংকটা আর কাজ করছে না", true);
                            selectTab(TAB_NEW, true);
                            return;
                        }
                        String[] pick = rowsFound.get(0);
                        for (String[] r : rowsFound) {
                            if (r[1] != null && r[1].equals(quality)) { pick = r; break; }
                        }
                        store.remove(job.id);
                        startDownload(pick[0], pick[1], pick[2], pick.length > 3 ? pick[3] : "",
                                title, id);
                    }
                });
            }
        }).start();
    }

    private void open(Store.Job job) {
        Uri uri = namedUri(job.name);
        if (uri == null && job.uri != null && job.uri.length() > 0) {
            try { uri = Uri.parse(job.uri); } catch (Exception ignored) { }
        }
        if (uri == null && job.dmId > 0) {
            try {
                DownloadManager d = dm();
                if (d != null) uri = d.getUriForDownloadedFile(job.dmId);
            } catch (Exception ignored) { }
        }
        if (uri == null) {
            toast("ফাইলটা Downloads ফোল্ডারে আছে");
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if ("file".equals(uri.getScheme())) allowFileUri();
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("চালানোর জন্য কোনো অ্যাপ নেই");
        }
    }

    /** the Downloads entry for this file, which other apps can actually read */
    private Uri namedUri(String name) {
        if (Build.VERSION.SDK_INT < 29 || name == null || name.length() == 0) return null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{name},
                    MediaStore.Downloads.DATE_ADDED + " DESC");
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    /** old Android refuses file:// intents; this silences that check for one launch */
    private static void allowFileUri() {
        try {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- shell

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(header());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(4), 0, 0);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(30));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF6E0A15, 0xFF9E1122, BRAND});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(22), dp(22), dp(22), dp(22)});
        box.setBackground(bg);
        box.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView title = text("Skyble Tube", 23, 0xFFFFFFFF, true);
        title.setLetterSpacing(0.01f);
        box.addView(title);

        TextView sub = text("ইউটিউব ভিডিও ডাউনলোডার", 12, 0xFFF3C6CC, false);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        box.addView(sub);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(pill(0x33FFFFFF, 0));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(16);
        bar.setLayoutParams(barLp);
        for (int i = 0; i < 3; i++) {
            final int which = i;
            TextView t = text("", 13, 0xFFFFFFFF, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(9), dp(8), dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(which, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(lp);
            tabs[i] = t;
            bar.addView(t);
        }
        box.addView(bar);
        return box;
    }

    private void selectTab(int which, boolean scrollTop) {
        tab = which;
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            if (i == which) {
                tabs[i].setBackground(pill(0xFFFFFFFF, 0));
                tabs[i].setTextColor(BRAND_DEEP);
            } else {
                tabs[i].setBackground(null);
                tabs[i].setTextColor(0xFFF6D2D6);
            }
        }
        updateTabLabels();
        render();
        if (scrollTop && scroll != null) scroll.scrollTo(0, 0);
    }

    private void updateTabLabels() {
        setTabText(TAB_NEW, "ডাউনলোড", 0);
        setTabText(TAB_RUNNING, "চলছে", activeCount());
        setTabText(TAB_DONE, "সম্পন্ন", doneCount());
    }

    private void setTabText(int which, String label, int count) {
        if (tabs[which] == null) return;
        tabs[which].setText(count > 0 ? label + "  " + count : label);
    }

    private int activeCount() {
        return store.byState(Store.QUEUED, Store.RUNNING).size();
    }

    private int doneCount() {
        return store.count(Store.DONE);
    }

    private void afterChange(boolean rerender) {
        updateTabLabels();
        if (rerender && tab != TAB_NEW) render();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        rows.clear();
        if (tab == TAB_NEW) renderNew();
        else if (tab == TAB_RUNNING) renderRunning();
        else renderDone();
    }

    // ------------------------------------------------------------- tab: new

    private void renderNew() {
        content.removeAllViews();
        rows.clear();

        LinearLayout card = card();
        card.addView(sectionLabel("ইউটিউব লিংক"));
        final EditText input = new EditText(this);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(INK);
        input.setHintTextColor(0xFF9AA2B1);
        input.setHint("youtube.com/watch?v=…  অথবা youtu.be/…");
        input.setSingleLine(true);
        input.setBackground(rounded(0xFFF6F7FB, 12, LINE));
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        input.setText(linkText);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                linkText = s.toString();
            }
        });
        linkBox = input;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(10);
        input.setLayoutParams(lp);
        card.addView(input);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView paste = chip("পেস্ট", 0xFFF1F3F9, INK);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = clipboard();
                if (text == null || text.length() == 0) {
                    toast("ক্লিপবোর্ডে কিছু নেই");
                    return;
                }
                String link = firstLink(text);
                input.setText(link == null ? text : link);
                input.setSelection(input.getText().length());
            }
        });
        actions.addView(paste);
        TextView go = button(searching ? "আবার খোঁজো" : "খুঁজুন", BRAND, 0xFFFFFFFF);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { search(); }
        });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        goLp.leftMargin = dp(8);
        go.setLayoutParams(goLp);
        actions.addView(go);
        card.addView(actions);
        content.addView(card);

        if (statusText != null && statusText.length() > 0) {
            TextView st = text(statusText, 13, statusBad ? BAD : MUTED, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(10);
            slp.leftMargin = dp(4);
            slp.rightMargin = dp(4);
            slp.bottomMargin = dp(2);
            st.setLayoutParams(slp);
            content.addView(st);
        }

        if (searching) {
            content.addView(loadingCard());
            return;
        }
        if (found.isEmpty()) {
            content.addView(emptyCard("লিংক দিয়ে খুঁজলে এখানে কোয়ালিটি দেখাবে",
                    "৩৬০p থেকে ১০৮০p পর্যন্ত, সাইজসহ"));
            return;
        }

        LinearLayout list = card();
        list.addView(sectionLabel("কোয়ালিটি"));
        for (final String[] row : found) {
            list.addView(resultRow(row));
        }
        content.addView(list);
    }

    private View resultRow(final String[] row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(4), dp(10), dp(4), dp(10));
        line.setBackground(rounded(0xFFF8F9FC, 12, LINE));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView q = text(row[1], 15, INK, true);
        col.addView(q);
        String size = row.length > 2 ? row[2] : "";
        boolean split = row.length > 3 && row[3] != null && row[3].length() > 0;
        String sub = (split ? "ছবি + অডিও" : "এক ফাইল") + (size.length() > 0 ? " · " + size : "");
        col.addView(text(sub, 12, MUTED, false));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(colLp);
        line.addView(col);

        TextView get = chip("নামাও", SOFT, BRAND);
        get.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload(row[0], row[1], row.length > 2 ? row[2] : "",
                        row.length > 3 ? row[3] : "", videoTitle, curVideoId);
            }
        });
        line.addView(get);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        line.setLayoutParams(lp);
        return line;
    }

    private View loadingCard() {
        LinearLayout card = card();
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(BRAND));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(6);
        bar.setLayoutParams(lp);
        card.addView(bar);
        TextView t = text("ইউটিউব থেকে তথ্য আনা হচ্ছে…", 13, MUTED, false);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(10);
        t.setLayoutParams(tp);
        card.addView(t);
        return card;
    }

    // --------------------------------------------------------- tab: running

    private void renderRunning() {
        content.removeAllViews();
        rows.clear();

        List<Store.Job> active = store.byState(Store.QUEUED, Store.RUNNING);
        List<Store.Job> failed = store.byState(Store.FAILED);

        if (active.isEmpty() && failed.isEmpty()) {
            content.addView(emptyCard("এখন কিছু নামছে না",
                    doneCount() > 0 ? "নামানো ফাইল সম্পন্ন ট্যাবে আছে"
                            : "ডাউনলোড ট্যাবে লিংক দিয়ে শুরু করো"));
            return;
        }

        for (Store.Job job : active) content.addView(jobCard(job, false));
        if (!failed.isEmpty()) {
            TextView l = sectionLabel("শেষ হয়নি");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            lp.leftMargin = dp(4);
            l.setLayoutParams(lp);
            content.addView(l);
            for (Store.Job job : failed) content.addView(jobCard(job, true));
        }
    }

    private View jobCard(final Store.Job job, final boolean failed) {
        LinearLayout card = card();

        TextView title = text(job.title == null || job.title.length() == 0
                ? job.name : job.title, 15, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrLp.topMargin = dp(4);
        metaRow.setLayoutParams(mrLp);

        TextView meta = text(metaLine(job), 12, failed ? BAD : MUTED, false);
        metaRow.addView(meta, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = chip(failed ? "বাদ দাও" : "বাতিল",
                failed ? 0xFFF1F3F9 : SOFT, failed ? MUTED : BAD);
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (failed) {
                    store.remove(job.id);
                    afterChange(true);
                } else {
                    cancel(job);
                }
            }
        });
        metaRow.addView(action);
        card.addView(metaRow);

        Row row = new Row();
        row.meta = meta;

        if (!failed) {
            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(ColorStateList.valueOf(BRAND));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFEDEFF5));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
            blp.topMargin = dp(10);
            bar.setLayoutParams(blp);
            card.addView(bar);
            row.bar = bar;

            row.pct = text(progressLine(job), 12, MUTED, false);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(7);
            row.pct.setLayoutParams(plp);
            card.addView(row.pct);
            rows.put(job.id, row);
            applyProgress(job, row);
        } else {
            TextView retry = chip("আবার চেষ্টা", SOFT, BRAND);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(10);
            retry.setLayoutParams(rlp);
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retry(job);
                }
            });
            card.addView(retry);
        }
        return card;
    }

    private String metaLine(Store.Job job) {
        StringBuilder b = new StringBuilder();
        if (job.quality != null && job.quality.length() > 0) b.append(job.quality);
        if (job.audioUrl != null && job.audioUrl.length() > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append("ছবি + অডিও");
        }
        if (job.message != null && job.message.length() > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(job.message);
        }
        return b.length() == 0 ? "প্রস্তুত হচ্ছে" : b.toString();
    }

    private String progressLine(Store.Job job) {
        if (job.total > 0) {
            long done = Math.min(job.done, job.total);
            int pct = (int) Math.min(99, done * 100 / job.total);
            return pct + "% · " + Muxer.sizeText(done) + " / " + Muxer.sizeText(job.total);
        }
        return Muxer.sizeText(job.done);
    }

    private void applyProgress(Store.Job job, Row row) {
        if (job.total > 0) {
            row.bar.setIndeterminate(false);
            long shown = Math.min(job.done, job.total);
            row.bar.setProgress((int) Math.min(990, shown * 1000 / job.total));
        } else {
            row.bar.setIndeterminate(true);
        }
        row.pct.setText(progressLine(job));
        if (row.meta != null) row.meta.setText(metaLine(job));
    }

    private void updateRow(Store.Job job) {
        Row row = rows.get(job.id);
        if (row != null && row.bar != null) applyProgress(job, row);
        updateTabLabels();
    }

    // ------------------------------------------------------------ tab: done

    private void renderDone() {
        content.removeAllViews();
        rows.clear();

        List<Store.Job> done = store.byState(Store.DONE);
        if (done.isEmpty()) {
            content.addView(emptyCard("এখনো কিছু নামানো হয়নি",
                    "নামানো ভিডিও এখানে জমা থাকবে"));
            return;
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.bottomMargin = dp(8);
        head.setLayoutParams(hl);
        TextView count = text(done.size() + "টা ফাইল · Downloads ফোল্ডারে", 12, MUTED, false);
        head.addView(count, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = chip("তালিকা মুছুন", 0xFFF1F3F9, MUTED);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.clearDone();
                afterChange(true);
            }
        });
        head.addView(clear);
        content.addView(head);

        for (final Store.Job job : done) content.addView(doneCard(job));
    }

    private View doneCard(final Store.Job job) {
        LinearLayout card = card();
        TextView title = text(job.title == null || job.title.length() == 0
                ? job.name : job.title, 15, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        StringBuilder meta = new StringBuilder();
        if (job.total > 0) meta.append(Muxer.sizeText(job.total));
        if (job.quality != null && job.quality.length() > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(job.quality);
        }
        if (job.finished > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(Store.whenText(job.finished));
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(4);
        TextView m = text(meta.toString(), 12, MUTED, false);
        m.setLayoutParams(mlp);
        card.addView(m);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        actions.setLayoutParams(alp);

        TextView open = chip("খোলো", SOFT, BRAND);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { open(job); }
        });
        actions.addView(open);

        TextView remove = chip("তালিকা থেকে সরাও", 0xFFF1F3F9, MUTED);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        remove.setLayoutParams(rlp);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.remove(job.id);
                afterChange(true);
            }
        });
        actions.addView(remove);
        card.addView(actions);
        return card;
    }

    // ---------------------------------------------------------------- pieces

    private String linkText = "";

    private void setStatus(String text, boolean bad) {
        statusText = text == null ? "" : text;
        statusBad = bad;
        if (tab == TAB_NEW) renderNew();
    }

    private String clipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence s = clip.getItemAt(0).coerceToText(this);
            return s == null ? null : s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String label(Store.Job job) {
        String t = job.title == null || job.title.length() == 0 ? "Skyble Tube" : job.title;
        return t + (job.quality == null || job.quality.length() == 0 ? "" : " · " + job.quality);
    }

    private static long parseSize(String text) {
        if (text == null) return 0;
        try {
            String s = text.trim().toUpperCase(Locale.US);
            double mul = 1;
            if (s.endsWith("KB")) { mul = 1024; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("MB")) { mul = 1048576; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("GB")) { mul = 1073741824; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("B")) { s = s.substring(0, s.length() - 1); }
            return (long) (Double.parseDouble(s.trim().replace(",", ".")) * mul);
        } catch (Exception e) {
            return 0;
        }
    }

    private void persist(Store.Job job, boolean force) {
        long now = System.currentTimeMillis();
        Long last = lastSave.get(job.id);
        if (!force && last != null && now - last < 2000) return;
        lastSave.put(job.id, now);
        store.save(job);
    }

    private void reconcile() {
        for (Store.Job job : store.byState(Store.QUEUED, Store.RUNNING)) {
            if (job.dmId > 0) {
                dmIndex.put(job.dmId, job.id);
            } else if (Store.RUNNING.equals(job.state)) {
                job.state = Store.FAILED;
                job.message = "অ্যাপ বন্ধ হয়ে গিয়েছিল";
                store.save(job);
            }
        }
    }

    // --------------------------------------------------------------- drawing

    private View emptyCard(String title, String sub) {
        LinearLayout card = card();
        TextView t = text(title, 15, INK, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        TextView s = text(sub, 12, MUTED, false);
        s.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        s.setLayoutParams(lp);
        card.addView(s);
        card.setPadding(dp(18), dp(28), dp(18), dp(28));
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 16, LINE));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionLabel(String text) {
        TextView t = text(text, 11, MUTED, true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chip(String value, int bgColor, int textColor) {
        TextView t = text(value, 13, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pill(bgColor, 0));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        return t;
    }

    private TextView button(String value, int bgColor, int textColor) {
        TextView t = text(value, 15, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(bgColor, 12, 0));
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable rounded(int color, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable pill(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) return e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- permission

    private boolean wantsWritePermission() {
        return Build.VERSION.SDK_INT <= 28;
    }

    private boolean hasWritePermission() {
        if (!wantsWritePermission()) return true;
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askWritePermission() {
        if (wantsWritePermission() && !hasWritePermission()) requestWritePermission();
    }

    private void requestWritePermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_WRITE) return;
        if (!hasWritePermission()) {
            setStatus("অনুমতি ছাড়া ফাইল Downloads-এ রাখা যাবে না", true);
            return;
        }
        if (pendingUrl != null) {
            String u = pendingUrl, q = pendingQuality, s = pendingSize;
            String a = pendingAudio, t = pendingTitle, id = pendingId;
            pendingUrl = null;
            pendingAudio = null;
            startDownload(u, q, s, a, t, id);
        }
    }
}
