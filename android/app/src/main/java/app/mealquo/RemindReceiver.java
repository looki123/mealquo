package app.mealquo;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RemindReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Remind.reschedule(context);
            return;
        }
        String kind = intent == null ? "" : intent.getStringExtra("kind");
        SharedPreferences p = context.getSharedPreferences(Remind.PREF, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        boolean same = today.equals(p.getString("day", ""));
        int meals = same ? p.getInt("meals", 0) : 0;
        int eaten = same ? p.getInt("eaten", 0) : 0;
        int goal = p.getInt("goal", 0);
        String lang = p.getString("lang", "pl");
        boolean sound = p.getBoolean("sound", true);
        if (intent == null) return;
        String title;
        String text;
        if ("planlunch".equals(kind) || "plandinner".equals(kind)) {
            boolean done = "planlunch".equals(kind) ? p.getBoolean("planLunch", false) : p.getBoolean("planDinner", false);
            if (!done) {
                boolean lunch = "planlunch".equals(kind);
                title = lunch
                        ? ("uk".equals(lang) ? "Перед обідом" : "en".equals(lang) ? "Before lunch" : "Przed lunchem")
                        : ("uk".equals(lang) ? "Плани на вечір" : "en".equals(lang) ? "Plans for tonight" : "Plany na wieczór");
                text = lunch
                        ? ("uk".equals(lang) ? "Що перед обідом: замовлення, готування чи ресторан?" : "en".equals(lang) ? "Before lunch: order, cook, or a restaurant?" : "Co przed lunchem: zamówienie, gotowanie czy restauracja?")
                        : ("uk".equals(lang) ? "Що ввечері: замовлення, готування чи ресторан?" : "en".equals(lang) ? "Tonight: order, cook, or a restaurant?" : "Co na wieczór: zamówienie, gotowanie czy restauracja?");
                show(context, lunch ? 41 : 42, title, text, sound);
            }
            Remind.reschedule(context);
            return;
        }
        if ("evening".equals(kind)) {
            title = "uk".equals(lang) ? "Вечірній підсумок" : "en".equals(lang) ? "Evening summary" : "Wieczorne podsumowanie";
            text = meals == 0
                    ? ("uk".equals(lang) ? "Сьогодні ще нічого не додано." : "en".equals(lang) ? "Nothing logged today yet." : "Dziś jeszcze nic nie dodane.")
                    : (eaten + " / " + goal + " kcal");
        } else {
            int from = intent.getIntExtra("from", 0);
            int to = intent.getIntExtra("to", 0);
            if (same && covered(p.getString("logged", ""), from, to)) {
                Remind.reschedule(context);
                return;
            }
            String usual = intent.getStringExtra("usual");
            if (usual == null || usual.isEmpty()) usual = "";
            title = "uk".equals(lang) ? "Звична пора" : "en".equals(lang) ? "Usual time" : "Zwykła pora";
            text = "uk".equals(lang)
                    ? "Зазвичай близько " + usual + " ти щось додаєш. Зараз порожньо."
                    : "en".equals(lang)
                    ? "You usually log something around " + usual + ". This slot is still empty."
                    : "Zwykle około " + usual + " coś dodajesz. Ta pora jest jeszcze pusta.";
        }
        show(context, "evening".equals(kind) ? 40 : 50 + Math.max(0, intent.getIntExtra("from", 0) / 20), title, text, sound);
        Remind.reschedule(context);
    }

    private static boolean covered(String raw, int from, int to) {
        try {
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                int min = arr.optInt(i, -1);
                if (min >= from && min <= to) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void show(Context context, int id, String title, String text, boolean sound) {
        String channel = "mealquo";
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channel, "Mealquo", sound ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Przypomnienia Mealquo");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 21, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true);
        if (!sound) b.setSilent(true);
        try { nm.notify(id, b.build()); } catch (Exception ignored) {}
    }
}
