package app.mealquo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.net.HttpURLConnection;
import java.security.MessageDigest;

// Nagłówki X-Android-Package / X-Android-Cert: pozwalają ograniczyć klucze Google
// w konsoli do aplikacji Mealquo (nazwa pakietu + SHA-1 certyfikatu podpisu).
final class AppKey {
    static String pkg = "";
    static String cert = "";

    static void init(Context context) {
        if (!cert.isEmpty()) return;
        try {
            pkg = context.getPackageName();
            PackageManager pm = context.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = info.signingInfo.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : sha) hex.append(String.format("%02X", b));
            cert = hex.toString();
        } catch (Exception ignored) {
        }
    }

    static void sign(HttpURLConnection conn) {
        if (pkg.isEmpty() || cert.isEmpty()) return;
        conn.setRequestProperty("X-Android-Package", pkg);
        conn.setRequestProperty("X-Android-Cert", cert);
    }

    static String json() {
        return "{\"X-Android-Package\":\"" + pkg + "\",\"X-Android-Cert\":\"" + cert + "\"}";
    }
}
