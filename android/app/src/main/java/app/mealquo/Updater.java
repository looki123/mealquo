package app.mealquo;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

import okhttp3.Request;
import okhttp3.Response;

// Aktualizacje wersji z GitHuba (tylko APK spoza Google Play, BuildConfig.UPDATES):
// sprawdzenie najnowszego wydania najwyżej co 6 h. Cicha aktualizacja (Android 12+): APK pobiera się w tle,
// a PackageInstaller instaluje go bez pytania, gdy użytkownik wychodzi z aplikacji (MainActivity.onStop).
// Gdy system mimo to poprosi o potwierdzenie, wracamy do karty z przyciskiem (pref "loud").
// Podpis APK z GitHuba jest stały (debug.keystore), więc aktualizacja zachowuje dane.
final class Updater {
    private static final String API = "https://api.github.com/repos/looki123/mealquo/releases/latest";
    private static final String PREF = "mealquo_update";
    private static final long EVERY = 2L * 60 * 1000; // przy każdym otwarciu; 2 min chroni przed limitem GitHuba (60/h)

    static void check(Context ctx, Consumer<String> say) {
        check(ctx, say, false);
    }

    // force: przycisk „Sprawdź aktualizacje” w profilu – bez limitu 6 h; gdy brak nowszej, window.onUpdateNone
    static void check(Context ctx, Consumer<String> say, boolean force) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = p.getLong("at", 0);
        String cached = p.getString("found", "");
        if (!force && System.currentTimeMillis() - last < EVERY) {
            if (!cached.isEmpty() && newerThanMe(cached)) tell(ctx, say, cached);
            return;
        }
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(API)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Mealquo/" + BuildConfig.VERSION_NAME).build();
                try (Response res = Gemini.HTTP.newCall(req).execute()) {
                    if (!res.isSuccessful() || res.body() == null) return;
                    JSONObject o = new JSONObject(res.body().string());
                    String tag = o.optString("tag_name", "").replaceFirst("^v", "");
                    JSONArray assets = o.optJSONArray("assets");
                    String url = "";
                    long size = 0;
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && a.optString("name", "").endsWith(".apk")) { url = a.optString("browser_download_url", ""); size = a.optLong("size", 0); }
                    }
                    String found = "";
                    if (!tag.isEmpty() && !url.isEmpty() && newer(tag, BuildConfig.VERSION_NAME)) {
                        found = new JSONObject().put("version", tag).put("url", url).put("size", size).toString();
                    }
                    p.edit().putLong("at", System.currentTimeMillis()).putString("found", found).apply();
                    // karta zawsze; przy cichej aktualizacji APK pobiera się od razu w tle
                    if (!found.isEmpty()) tell(ctx, say, found);
                    if (!found.isEmpty() && !force && silentOk(ctx)) fetchQuiet(ctx, tag, url);
                    else if (force) say.accept("window.onUpdateNone && window.onUpdateNone()");
                }
            } catch (Exception e) {
                if (force) say.accept("window.onUpdateNone && window.onUpdateNone(true)");
                CrashLog.add("update check " + e);
            }
        }).start();
    }

    // quiet: zainstaluje się sama po wyjściu z aplikacji (karta mówi to zamiast „Później”)
    private static void tell(Context ctx, Consumer<String> say, String json) {
        try {
            json = new JSONObject(json).put("quiet", silentOk(ctx)).toString();
        } catch (Exception ignored) {}
        say.accept("window.onUpdate && window.onUpdate(" + JSONObject.quote(json) + ")");
    }

    private static boolean newerThanMe(String json) {
        try {
            return newer(new JSONObject(json).optString("version", ""), BuildConfig.VERSION_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    // 15.10 > 15.9: porównanie po liczbach, nie po napisach
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0, yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) return xi > yi;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    // cicho: Android 12+, zgoda na instalowanie z tej aplikacji i system wcześniej nie zażądał potwierdzenia
    static boolean silentOk(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        if (ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("loud", false)) return false;
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static File quietFile(Context ctx) {
        return new File(new File(ctx.getCacheDir(), "updates"), "quiet.apk");
    }

    // pobranie w tle bez paska postępu; instalacja dopiero przy wyjściu z aplikacji
    private static void fetchQuiet(Context ctx, String version, String url) {
        if (!url.startsWith("https://github.com/looki123/mealquo/")) return;
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        File out = quietFile(ctx);
        if (version.equals(p.getString("ready", "")) && out.length() > 0) return;
        try {
            out.getParentFile().mkdirs();
            File part = new File(out.getParentFile(), "quiet.part");
            Request req = new Request.Builder().url(url).header("User-Agent", "Mealquo/" + BuildConfig.VERSION_NAME).build();
            try (Response res = Gemini.HTTP.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) return;
                try (InputStream in = res.body().byteStream(); FileOutputStream fos = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            if (!part.renameTo(out)) return;
            p.edit().putString("ready", version).apply();
        } catch (Exception e) {
            CrashLog.add("update quiet download " + e);
        }
    }

    // wołane z MainActivity.onStop: jest gotowy nowszy APK – instalacja bez pytania (aplikacja jest w tle)
    static void installReady(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String version = p.getString("ready", "");
        File apk = quietFile(ctx);
        if (version.isEmpty() || !apk.exists()) return;
        if (!newer(version, BuildConfig.VERSION_NAME) || !silentOk(ctx)) {
            p.edit().remove("ready").apply();
            apk.delete();
            return;
        }
        p.edit().remove("ready").apply();
        new Thread(() -> {
            try {
                session(ctx, apk, true);
            } catch (Exception e) {
                CrashLog.add("update quiet install " + e);
            }
        }).start();
    }

    // instalacja przez PackageInstaller; wynik (i ewentualna prośba o potwierdzenie) w UpdateReceiver
    static void session(Context ctx, File apk, boolean quiet) throws Exception {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("mealquo.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(ctx, UpdateReceiver.class).putExtra("quiet", quiet);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, id, done, flags).getIntentSender());
        }
    }

    // system poprosił o potwierdzenie przy cichej aktualizacji: dalej karta z przyciskiem
    static void loud(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("loud", true).putLong("at", 0).apply();
    }

    static void download(Context ctx, String url, Consumer<String> say) {
        if (url == null || !url.startsWith("https://github.com/looki123/mealquo/")) return;
        new Thread(() -> {
            try {
                File dir = new File(ctx.getCacheDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                for (File old : dir.listFiles() == null ? new File[0] : dir.listFiles()) old.delete();
                File out = new File(dir, "mealquo-update.apk");
                Request req = new Request.Builder().url(url).header("User-Agent", "Mealquo/" + BuildConfig.VERSION_NAME).build();
                try (Response res = Gemini.HTTP.newCall(req).execute()) {
                    if (!res.isSuccessful() || res.body() == null) { say.accept("window.onUpdateProgress && window.onUpdateProgress(-1)"); return; }
                    long total = res.body().contentLength();
                    long got = 0;
                    int lastPct = -1;
                    try (InputStream in = res.body().byteStream(); FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            got += n;
                            int pct = total > 0 ? (int) (got * 100 / total) : 0;
                            if (pct != lastPct && (pct % 2 == 0 || pct == 100)) { lastPct = pct; say.accept("window.onUpdateProgress && window.onUpdateProgress(" + pct + ")"); }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
                    // brak zgody na instalowanie z tej aplikacji: ustawienia systemowe, aplikacja tłumaczy co zrobić
                    say.accept("window.onUpdateProgress && window.onUpdateProgress(-2)");
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return;
                }
                say.accept("window.onUpdateProgress && window.onUpdateProgress(100)");
                session(ctx, out, false);
            } catch (Exception e) {
                CrashLog.add("update download " + e);
                say.accept("window.onUpdateProgress && window.onUpdateProgress(-1)");
            }
        }).start();
    }

    private Updater() {}
}
