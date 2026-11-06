package app.mealquo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

// Zapytania HTTP poza Gemini (Places, OSM, zdjęcia dań). Wspólna pula połączeń z Gemini.HTTP.
final class Net {
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    // do równoległych zapytań (lokale obok, zdjęcia dań)
    static final ExecutorService POOL = Executors.newCachedThreadPool();

    static String get(String url, String agent, int readMs) {
        byte[] body = fetch(new Request.Builder().url(url).header("User-Agent", agent).build(), readMs, 4_000_000);
        return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String url, String agent, int readMs, int max) {
        return fetch(new Request.Builder().url(url).header("User-Agent", agent).build(), readMs, max);
    }

    // Google Places z kluczem Maps i nagłówkami aplikacji (klucz ograniczony do Mealquo)
    static String google(String url, String json, String fieldMask, int readMs) {
        Request.Builder req = new Request.Builder().url(url).header("X-Goog-Api-Key", Maps.KEY);
        if (fieldMask != null) req.header("X-Goog-FieldMask", fieldMask);
        if (!AppKey.pkg.isEmpty() && !AppKey.cert.isEmpty()) {
            req.header("X-Android-Package", AppKey.pkg).header("X-Android-Cert", AppKey.cert);
        }
        if (json != null) req.post(RequestBody.create(json, JSON_TYPE));
        byte[] body = fetch(req.build(), readMs, 4_000_000);
        return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    // treść odpowiedzi 2xx albo null (błąd, przekroczony rozmiar)
    private static byte[] fetch(Request req, int readMs, int max) {
        OkHttpClient client = Gemini.HTTP.newBuilder()
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .callTimeout(readMs + 4000L, TimeUnit.MILLISECONDS)
                .build();
        try (Response res = client.newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) return null;
            if (body.contentLength() > max) return null;
            byte[] bytes = body.bytes();
            return bytes.length > max ? null : bytes;
        } catch (Exception e) {
            return null;
        }
    }

    private Net() {}
}
