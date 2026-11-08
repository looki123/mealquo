package app.mealquo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;

import okhttp3.Request;
import okhttp3.Response;

// Aktualizacje wersji z GitHuba (tylko APK spoza Google Play, BuildConfig.UPDATES):
// sprawdzenie najnowszego wydania najwyżej co 6 h, pobranie APK do cache z postępem i otwarcie instalatora.
// Podpis APK z GitHuba jest stały (debug.keystore), więc aktualizacja zachowuje dane.
final class Updater {
    private static final String API = "https://api.github.com/repos/looki123/mealquo/releases/latest";
    private static final String PREF = "mealquo_update";
    private static final long EVERY = 6L * 3600 * 1000;

    static void check(Context ctx, Consumer<String> say) {
        check(ctx, say, false);
    }

    // force: przycisk „Sprawdź aktualizacje” w profilu – bez limitu 6 h; gdy brak nowszej, window.onUpdateNone
    static void check(Context ctx, Consumer<String> say, boolean force) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = p.getLong("at", 0);
        String cached = p.getString("found", "");
        if (!force && System.currentTimeMillis() - last < EVERY) {
            if (!cached.isEmpty()) tell(say, cached);
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
                    if (!found.isEmpty()) tell(say, found);
                    else if (force) say.accept("window.onUpdateNone && window.onUpdateNone()");
                }
            } catch (Exception e) {
                if (force) say.accept("window.onUpdateNone && window.onUpdateNone(true)");
                CrashLog.add("update check " + e);
            }
        }).start();
    }

    private static void tell(Consumer<String> say, String json) {
        say.accept("window.onUpdate && window.onUpdate(" + JSONObject.quote(json) + ")");
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
                Uri uri = FileProvider.getUriForFile(ctx, "app.mealquo.fileprovider", out);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                say.accept("window.onUpdateProgress && window.onUpdateProgress(100)");
                ctx.startActivity(install);
            } catch (Exception e) {
                CrashLog.add("update download " + e);
                say.accept("window.onUpdateProgress && window.onUpdateProgress(-1)");
            }
        }).start();
    }

    private Updater() {}
}
