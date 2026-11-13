package app.mealquo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

// Wynik instalacji aktualizacji (Updater.session). Po sukcesie system sam zamyka starą wersję.
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        boolean quiet = intent.getBooleanExtra("quiet", false);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (quiet) {
                // aplikacja jest w tle, okna nie pokażemy; od teraz aktualizacja z karty w aplikacji
                Updater.loud(ctx);
                return;
            }
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(confirm);
            } catch (Exception e) {
                CrashLog.add("update confirm " + e);
            }
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            CrashLog.add("update install " + status + " " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
