package app.mealquo;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Dziennik awarii i błędów JS w pamięci aplikacji; użytkownik wysyła go z profilu.
final class CrashLog {
    private static final long MAX = 64 * 1024;
    private static File file;

    static void install(Context context) {
        file = new File(context.getFilesDir(), "errors.log");
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            add("CRASH " + thread.getName() + "\n" + trace);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static synchronized void add(String text) {
        if (file == null || text == null) return;
        try {
            // przy przepełnieniu zostaje druga połowa
            if (file.length() > MAX) {
                String keep = read();
                keep = keep.substring(keep.length() / 2);
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(keep.getBytes(StandardCharsets.UTF_8));
                }
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = stamp + " v" + BuildConfig.VERSION_NAME + " " + text + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    static synchronized String read() {
        if (file == null || !file.exists()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear() {
        if (file != null) file.delete();
    }

    private CrashLog() {}
}
