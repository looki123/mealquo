package app.mealquo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class Gemini {
    static final String KEY = BuildConfig.GEMINI_KEY;
    static final String[] MODELS = {
            "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3-flash-preview"
    };

    static String lang = "pl";
    static String tier = "lite";

    static String[] order() {
        if ("flash".equals(tier)) {
            return new String[]{"gemini-3-flash-preview", "gemini-3.5-flash-lite", "gemini-flash-lite-latest"};
        }
        return MODELS;
    }

    static String hintLang() {
        if ("uk".equals(lang)) return "українською";
        if ("en".equals(lang)) return "in English";
        return "po polsku";
    }

    static String precise() {
        return " Nazwa maksymalnie konkretna, nigdy jednym słowem. Zakazane same: mięso, danie, sałatka, zupa, ryba, kurczak, napój, makaron. "
                + "W nazwie podaj rodzaj, część, kolor, sposób przyrządzenia i dodatek albo sos, jeśli to widać. "
                + "Dobrze: pierś z kurczaka grillowana z ryżem i brokułem; schabowy panierowany z ziemniakami i mizerią; zupa pomidorowa z ryżem; jabłko zielone; latte z mlekiem. "
                + "Markę z etykiety wpisz, gdy da się ją odczytać. name_pl, name_uk i name_en mają być tak samo szczegółowe.";
    }

    static String stillPrompt() {
        return "Jesteś dietetykiem. Na zdjęciu jest jeden posiłek. Zwróć wyłącznie JSON: "
                + "{\"items\":[{\"name_pl\":\"sałatka z sałatą i grillowaną cukinią\",\"name_uk\":\"салат із латуку і грильованих кабачків\",\"name_en\":\"lettuce salad with grilled zucchini\",\"count\":1,\"grams\":280,\"kcal\":190,\"p\":6,\"c\":16,\"f\":11,\"box\":{\"x\":0.06,\"y\":0.32,\"w\":0.88,\"h\":0.48}}]}. "
                + "box to ułamek 0-1 tego zdjęcia, lewy górny róg. Ramka ma objąć CAŁY produkt, nie sam brzeg. Kawałek pizzy: od skórki aż po czubek z dodatkami. "
                + "Liczy się tylko pierwszy plan, największa rzecz blisko aparatu. Puszka, butelka i drugi talerz z tyłu nie wchodzą. "
                + "Sałata i grillowane warzywa to sałatka, nie ryba. Zupa to nie wino. "
                + "Sama puszka z przodu: marka i smak z etykiety, grams = ml. "
                + "Kiść winogron i garść orzechów: count to przybliżona liczba sztuk, nie 1. Max 3." + precise();
    }

    static String scanPrompt() {
        return "JSON: {\"ready\":true,\"hint\":\"\",\"items\":[{\"name_pl\":\"jabłko zielone\",\"name_uk\":\"зелене яблуко\",\"name_en\":\"green apple\",\"count\":1,\"grams\":150,\"kcal\":78,\"p\":0.4,\"c\":21,\"f\":0.2,\"box\":{\"x\":0.3,\"y\":0.25,\"w\":0.4,\"h\":0.45}}]}. "
                + "Jak detektor na żywo: każde widoczne danie albo napój to osobny item z własnym box. box to x,y,w,h od 0 do 1, lewy górny róg, ściśle wokół tej rzeczy. Nie cały kadr i nie 0,0. Nie piksele. Max 4. "
                + "Najbliższe duże danie na pierwszym planie jest obowiązkowe i tylko ono. Miska z sałatą, grillowaną cukinią i sosem to sałatka, nigdy ryba wędzona. "
                + "Puszka albo butelka stojąca z tyłu na stole, za miską, NIE wchodzi do items. Napój liczy się tylko gdy sam jest z przodu. "
                + "Puszka z przodu: marka i smak z etykiety, na przykład Garage Taste Lemon. grams = mililitry. box ciasno na tej rzeczy, nie na całym stole. "
                + "Nazwa szczegółowa: kolor i rodzaj, np. jabłko zielone. "
                + "count to liczba sztuk widocznych na zdjęciu. Kiść winogron, garść orzechów, miska jagód: przybliżona liczba, nigdy 1, jeśli widać wiele. "
                + "Butelka, puszka, karton, szklanka i kieliszek z pierwszego planu to napój i muszą trafić do items. Woda też, nawet przy 0 kcal. Tło za talerzem pomiń. "
                + "Zupa w misce to zupa, nie wino. Pomiń łyżkę, kubek i tło. "
                + "Pusto: ready=false, items=[], hint " + hintLang() + " do 4 słów." + precise();
    }

    static String platePrompt(String comment) {
        String extra = (comment == null || comment.trim().isEmpty())
                ? ""
                : " Użytkownik poprawia rozpoznanie. To jest ważniejsze niż samo zdjęcie: " + comment + ". "
                        + "Jeśli zjadł tylko część, grams i kcal to ta część (połowa = 50% kcal). "
                        + "Jeśli dopowiada sól, oliwę, sos albo składnik, dopisz to do nazwy i dolicz kcal. "
                        + "reason: jedno zdanie " + hintLang() + ", konkretnie co się zmieniło po komentarzu (porcja, składnik, kcal). Nie zostawiaj reason pustego. ";
        return "Na zdjęciu jest jedzenie albo napój." + extra
                + " Zwróć wyłącznie JSON: {\"reason\":\"\",\"items\":[{\"name_pl\":\"jabłko zielone\",\"name_uk\":\"зелене яблуко\",\"name_en\":\"green apple\",\"count\":1,\"grams\":150,\"kcal\":78,\"p\":0.4,\"c\":21,\"f\":0.2}]}."
                + " Najbliższe duże danie z przodu jest obowiązkowe. Miska sałaty i grillowanej cukinii to sałatka, nie ryba. "
                + "Puszka z tyłu za miską nie wchodzi do items. Puszka z przodu: marka z etykiety, grams = ml. "
                + "Winogrona, orzechy, jagody i chipsy: nie wpisuj count 1, jeśli widać kiść albo garść. Podaj przybliżoną liczbę. grams to cała porcja."
                + " Butelka wody też jest napojem: 0 kcal, grams = ml z etykiety."
                + " Kapelusz grzyba z blaszkami to grzyb. Duża beżowa kania to kania (czubajka kania), nie puste items i nie mięso."
                + " Nie dopisuj tła, drugiego kieliszka ani talerza z głębi, nawet jeśli coś tam widać. Max 3." + precise();
    }

    static String askImages(java.util.List<byte[]> jpegs, String prompt) {
        StringBuilder parts = new StringBuilder();
        for (byte[] jpeg : jpegs) {
            if (jpeg == null || jpeg.length == 0) continue;
            String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
            parts.append("{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"").append(b64).append("\"}},");
        }
        parts.append("{\"text\":").append(JSONObject.quote(prompt)).append("}");
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[" + parts
                + "]}],\"generationConfig\":{\"temperature\":0.2,\"responseMimeType\":\"application/json\"}}";
        return askModelsBody(body.getBytes(StandardCharsets.UTF_8));
    }

    static String ask(String imageB64, String prompt) {
        String text = askModels(imageB64, prompt);
        if (text.contains("\"items\":[]") || text.contains("\"items\": []")) {
            String again = askModels(imageB64, "Wypisz jedzenie i napoje tylko z pierwszego i drugiego planu. Pomiń tło i trzeci plan. "
                    + "Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":150,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}. grams = ml.");
            if (!again.contains("\"items\":[]")) return again;
        }
        return text;
    }

    static String askAudio(String wavB64, String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":["
                + "{\"inline_data\":{\"mime_type\":\"audio/wav\",\"data\":\"" + wavB64 + "\"}},"
                + "{\"text\":" + JSONObject.quote(prompt) + "}"
                + "]}],\"generationConfig\":{\"temperature\":0,\"responseMimeType\":\"application/json\"}}";
        return askModelsBody(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String askModels(String imageB64, String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":["
                + "{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"" + imageB64 + "\"}},"
                + "{\"text\":" + JSONObject.quote(prompt) + "}"
                + "]}],\"generationConfig\":{\"temperature\":0.2,\"responseMimeType\":\"application/json\"}}";
        return askModelsBody(body.getBytes(StandardCharsets.UTF_8));
    }

    static String askFast(String imageB64, String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":["
                + "{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"" + imageB64 + "\"}},"
                + "{\"text\":" + JSONObject.quote(prompt) + "}"
                + "]}],\"generationConfig\":{\"temperature\":0,\"maxOutputTokens\":220,\"responseMimeType\":\"application/json\",\"thinkingConfig\":{\"thinkingBudget\":0}}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String[] fast = order();
        int read = "flash".equals(tier) ? 20000 : 9000;
        for (String model : fast) {
            String text = postModel(model, bytes, 5000, read);
            if (text != null && text.contains("\"items\"") && !text.contains("\"items\":[]") && !text.contains("\"items\": []")) return text;
        }
        return "{\"items\":[]}";
    }

    private static String askModelsBody(byte[] bytes) {
        int read = "flash".equals(tier) ? 22000 : 12000;
        for (String model : order()) {
            String text = postModel(model, bytes, 5000, read);
            if (text != null) return text;
        }
        return "{\"items\":[]}";
    }

    private static String postModel(String model, byte[] bytes, int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + KEY);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            AppKey.sign(conn);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            JSONObject root = new JSONObject(raw.toString(StandardCharsets.UTF_8.name()));
            String text = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            text = text.trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            new JSONObject(text);
            return text;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String askText(String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":"
                + JSONObject.quote(prompt)
                + "}]}],\"generationConfig\":{\"temperature\":0.2,\"responseMimeType\":\"application/json\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        for (String model : order()) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent?key=" + KEY);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                AppKey.sign(conn);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
                int code = conn.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (in != null && (n = in.read(buf)) >= 0) raw.write(buf, 0, n);
                if (code == 404) continue;
                if (code == 429 || code == 503) continue;
                if (code < 200 || code >= 300) continue;
                JSONObject root = new JSONObject(raw.toString(StandardCharsets.UTF_8.name()));
                String text = root.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                text = text.trim();
                int start = text.indexOf('{');
                int end = text.lastIndexOf('}');
                if (start >= 0 && end > start) text = text.substring(start, end + 1);
                new JSONObject(text);
                return text;
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return "{\"items\":[]}";
    }

    static String highlights(String place, double lat, double lon) {
        String prompt = "Lokal z Google Maps: " + place
                + ". Wypisz wyłącznie nazwy dań z sekcji Menu Highlights, dokładnie jak na karcie, w oryginalnym języku. "
                + "JSON {\"names\":[\"Dumpling Soup\",\"Pho\"]}. Max 8. Bez tłumaczenia i bez markdown.";
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":"
                + JSONObject.quote(prompt)
                + "}]}],\"tools\":[{\"googleMaps\":{}}],\"toolConfig\":{\"retrievalConfig\":{\"latLng\":{\"latitude\":"
                + lat + ",\"longitude\":" + lon + "}}},\"generationConfig\":{\"temperature\":0}}";
        String text = postMaps(body.getBytes(StandardCharsets.UTF_8));
        if (text == null || !text.contains("names")) return "";
        try {
            org.json.JSONArray names = new JSONObject(text).optJSONArray("names");
            if (names == null || names.length() == 0) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < names.length(); i++) {
                String n = names.optString(i, "").trim();
                if (n.isEmpty()) continue;
                if (out.length() > 0) out.append(", ");
                out.append(n);
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String postMaps(byte[] bytes) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=" + KEY);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            AppKey.sign(conn);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            if (code < 200 || code >= 300) return null;
            JSONObject root = new JSONObject(raw.toString(StandardCharsets.UTF_8.name()));
            org.json.JSONArray parts = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.has("text")) all.append(part.getString("text"));
            }
            String text = all.toString().trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            new JSONObject(text);
            return text;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String askSearch(String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":"
                + JSONObject.quote(prompt)
                + "}]}],\"tools\":[{\"google_search\":{}}],\"generationConfig\":{\"temperature\":0.3}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        for (String model : order()) {
            String text = postSearch(model, bytes);
            if (text != null) return text;
        }
        return askText(prompt);
    }

    private static String postSearch(String model, byte[] bytes) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + KEY);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            AppKey.sign(conn);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            if (code < 200 || code >= 300) return null;
            JSONObject root = new JSONObject(raw.toString(StandardCharsets.UTF_8.name()));
            org.json.JSONArray parts = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.has("text")) all.append(part.getString("text"));
            }
            String text = all.toString().trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            new JSONObject(text);
            return text;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
