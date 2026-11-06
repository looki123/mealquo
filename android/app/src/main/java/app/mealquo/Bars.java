package app.mealquo;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

// Od targetSdk 35 Android 15+ zawsze rysuje aplikację pod paskami systemowymi.
// Na każdej wersji robimy tak samo: treść pod paskami, a kontener dostaje margines
// na paski, wycięcie ekranu i klawiaturę (zamiast adjustResize). Wcięcia nie idą dalej,
// więc WebView nie dolicza drugi raz env(safe-area-inset-*).
final class Bars {
    static void fit(Activity activity, View root, boolean lightBars) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(activity.getWindow(), root);
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);
    }

    private Bars() {}
}
