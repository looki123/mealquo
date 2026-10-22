package app.mealquo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Journal {
    static final int CREATE = 2101;
    static final int OPEN = 2102;
    private static final String PREF = "mealquo_sync";

    static Uri saved(Context ctx) {
        String raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("uri", "");
        if (raw == null || raw.isEmpty()) return null;
        return Uri.parse(raw);
    }

    static void remember(Activity activity, Uri uri) {
        if (uri == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}
        activity.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("uri", uri.toString()).apply();
    }

    static void create(Activity activity) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "mealquo.json");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(i, CREATE);
    }

    static void open(Activity activity) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(i, OPEN);
    }

    static void write(Context ctx, String json) throws Exception {
        Uri uri = saved(ctx);
        if (uri == null) throw new IllegalStateException("no-file");
        try (java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("no-stream");
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String read(Context ctx) throws Exception {
        Uri uri = saved(ctx);
        if (uri == null) return "";
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return "";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        }
    }

    static boolean linked(Context ctx) {
        return saved(ctx) != null && ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains("uri");
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
