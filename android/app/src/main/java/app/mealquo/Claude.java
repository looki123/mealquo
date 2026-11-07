package app.mealquo;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Drugi silnik: Claude Sonnet 5.5 przez serwer Mealquo (Cloudflare), klucz Anthropic zostaje na serwerze.
// /api/fix sam składa prompt talerza i przy błędzie Claude pyta Gemini.
final class Claude {
    private static final String URL = "https://mealquo.mealquo.workers.dev/api/fix";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    // JSON z items albo null (brak sieci, serwer niedostępny, oba modele zawiodły)
    static String fix(String imageB64, String comment) {
        if (BuildConfig.MEALQUO_TOKEN.isEmpty()) return null;
        try {
            JSONObject body = new JSONObject();
            body.put("image", imageB64 == null ? "" : imageB64);
            body.put("comment", comment == null ? "" : comment);
            body.put("lang", Gemini.lang);
            OkHttpClient client = Gemini.HTTP.newBuilder()
                    .readTimeout(45, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder()
                    .url(URL)
                    .header("X-Mealquo-App", BuildConfig.MEALQUO_TOKEN)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) return null;
                String text = new JSONObject(res.body().string()).optString("text", "");
                new JSONObject(text);
                return text;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Claude() {}
}
