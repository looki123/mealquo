package app.mealquo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

public class Remind {
    static final String PREF = "mealquo_remind";
    private static final String ACTION = "app.mealquo.REMIND";

    static void apply(Context ctx, String raw) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(raw == null ? "{}" : raw);
            p.edit()
                    .putString("cfg", o.toString())
                    .putString("day", o.optString("day", ""))
                    .putInt("meals", o.optInt("meals", 0))
                    .putInt("eaten", o.optInt("eaten", 0))
                    .putInt("goal", o.optInt("goal", 0))
                    .putBoolean("planLunch", o.optBoolean("planLunch", false))
                    .putBoolean("planDinner", o.optBoolean("planDinner", false))
                    .putString("logged", o.optJSONArray("logged") == null ? "" : o.optJSONArray("logged").toString())
                    .putString("lang", o.optString("lang", "pl"))
                    .putBoolean("sound", o.optBoolean("sound", true))
                    .apply();
        } catch (Exception ignored) {}
        reschedule(ctx);
    }

    static void reschedule(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONObject o;
        try { o = new JSONObject(p.getString("cfg", "{}")); }
        catch (Exception e) { o = new JSONObject(); }
        boolean smart = o.optBoolean("smart", true);
        boolean evening = o.optBoolean("evening", true);
        place(ctx, 11, "breakfast", null, 0, 0, "");
        place(ctx, 12, "lunch", null, 0, 0, "");
        place(ctx, 13, "dinner", null, 0, 0, "");
        JSONArray windows = smart ? o.optJSONArray("windows") : null;
        int count = windows == null ? 0 : Math.min(5, windows.length());
        for (int i = 0; i < 5; i++) {
            if (i < count) {
                JSONObject w = windows.optJSONObject(i);
                if (w == null) place(ctx, 21 + i, "habit", null, 0, 0, "");
                else place(ctx, 21 + i, "habit", w.optString("at"), w.optInt("from"), w.optInt("to"), w.optString("usual"));
            } else {
                place(ctx, 21 + i, "habit", null, 0, 0, "");
            }
        }
        place(ctx, 14, "evening", evening ? o.optString("eveningAt", "21:00") : null, 0, 0, "");
        place(ctx, 15, "planlunch", "10:15", 0, 0, "");
        place(ctx, 16, "plandinner", "16:15", 0, 0, "");
    }

    private static void place(Context ctx, int code, String kind, String hhmm, int from, int to, String usual) {
        Intent intent = new Intent(ctx, RemindReceiver.class);
        intent.setAction(ACTION);
        intent.putExtra("kind", kind);
        intent.putExtra("from", from);
        intent.putExtra("to", to);
        intent.putExtra("usual", usual == null ? "" : usual);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi);
        if (hhmm == null || !hhmm.contains(":")) return;
        String[] parts = hhmm.split(":");
        int hour, minute;
        try {
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return;
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 20000) c.add(Calendar.DAY_OF_YEAR, 1);
        long when = c.getTimeInMillis();
        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (Exception ignored) {}
    }
}
