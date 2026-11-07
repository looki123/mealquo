package app.mealquo;

import org.json.JSONObject;

// Drugi silnik: Claude Sonnet 5.5 przez serwer Mealquo (Cloudflare), klucz Anthropic zostaje na serwerze.
// /api/fix sam składa prompt talerza i przy błędzie Claude pyta Gemini.
final class Claude {
    // JSON z items albo null (brak sieci, serwer niedostępny, oba modele zawiodły)
    static String fix(String imageB64, String comment) {
        try {
            JSONObject body = new JSONObject();
            body.put("image", imageB64 == null ? "" : imageB64);
            body.put("comment", comment == null ? "" : comment);
            body.put("lang", Gemini.lang);
            Server.Reply reply = Server.post("/api/fix", body.toString(), 45);
            if (reply.status != 200) return null;
            String text = new JSONObject(reply.body).optString("text", "");
            new JSONObject(text);
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private Claude() {}
}
