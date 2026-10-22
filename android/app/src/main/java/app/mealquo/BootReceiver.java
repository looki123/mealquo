package app.mealquo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Po restarcie telefonu ustawia przypomnienia od nowa. Osobno od RemindReceiver,
// żeby ten mógł być nieeksportowany (inne aplikacje nie wywołają przypomnień).
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Remind.reschedule(context);
        }
    }
}
