package app.mealquo;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Serwer Mealquo (Worker Cloudflare): /api/fix (Claude), /api/journal (dziennik w bazie na VPS).
// Aplikację rozpoznaje po tokenie X-Mealquo-App (MEALQUO_TOKEN = sekret APP_TOKEN w Cloudflare).
final class Server {
    static final String BASE = "https://mealquo.mealquo.workers.dev";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    static final class Reply {
        final int status;
        final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
    }

    // status 0 = brak sieci albo tokenu
    static Reply post(String path, String json, int readSeconds) {
        return post(path, json, readSeconds, null);
    }

    // bearer: token sesji użytkownika (API v2)
    static Reply post(String path, String json, int readSeconds, String bearer) {
        if (BuildConfig.MEALQUO_TOKEN.isEmpty()) return new Reply(0, "");
        OkHttpClient client = Gemini.HTTP.newBuilder()
                .readTimeout(readSeconds, TimeUnit.SECONDS)
                .callTimeout(readSeconds + 15L, TimeUnit.SECONDS)
                .build();
        Request.Builder req = new Request.Builder()
                .url(BASE + path)
                .header("X-Mealquo-App", BuildConfig.MEALQUO_TOKEN)
                .post(RequestBody.create(json, JSON_TYPE));
        if (bearer != null && !bearer.isEmpty()) req.header("Authorization", "Bearer " + bearer);
        try (Response res = client.newCall(req.build()).execute()) {
            return new Reply(res.code(), res.body() == null ? "" : res.body().string());
        } catch (Exception e) {
            return new Reply(0, "");
        }
    }

    private Server() {}
}
