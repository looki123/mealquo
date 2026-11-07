package app.mealquo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


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


    // --- sieć ---
    // Jeden klient: połączenia (TLS, HTTP/2) są używane ponownie zamiast zestawiać je przy każdym zapytaniu.
    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    // łączny czas na wszystkie modele po kolei; potem komunikat zamiast dalszego czekania
    private static final long DEADLINE_MS = 25000;
    // mniej myślenia: flash odpowiada ~3x szybciej, lite bez zmian
    private static final String THINK = ",\"thinkingConfig\":{\"thinkingLevel\":\"low\"}";

    // rodzaj ostatniego błędu w tym wątku: offline, timeout, quota, key, server, bad
    private static final ThreadLocal<String> fail = new ThreadLocal<>();
    // MainActivity pokazuje komunikat, gdy żaden model nie odpowiedział
    static volatile Consumer<String> onFail;

    private static String generation(String extra) {
        return "\"generationConfig\":{\"temperature\":0.2,\"responseMimeType\":\"application/json\"" + THINK + extra + "}";
    }

    static String askImages(java.util.List<byte[]> jpegs, String prompt) {
        StringBuilder parts = new StringBuilder();
        for (byte[] jpeg : jpegs) {
            if (jpeg == null || jpeg.length == 0) continue;
            String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
            parts.append("{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"").append(b64).append("\"}},");
        }
        parts.append("{\"text\":").append(JSONObject.quote(prompt)).append("}");
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[" + parts + "]}]," + generation("") + "}";
        return orEmpty(tryModels(order(), bytes(body), readMs()));
    }

    static String ask(String imageB64, String prompt) {
        return orEmpty(tryModels(order(), bytes(mediaBody("image/jpeg", imageB64, prompt)), readMs()));
    }

    // jak ask, ale bez komunikatu o błędzie i z null przy porażce: wywołujący spróbuje jeszcze Claude
    static String askQuiet(String imageB64, String prompt) {
        return tryModels(order(), bytes(mediaBody("image/jpeg", imageB64, prompt)), readMs(), false);
    }

    // komunikat o ostatnim błędzie Gemini w tym wątku (po askQuiet, gdy zapas też zawiódł)
    static void reportLast() {
        report(fail.get());
    }

    static String askAudio(String wavB64, String prompt) {
        return orEmpty(tryModels(order(), bytes(mediaBody("audio/wav", wavB64, prompt)), readMs()));
    }

    static String askText(String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":" + JSONObject.quote(prompt) + "}]}],"
                + generation("") + "}";
        return orEmpty(tryModels(order(), bytes(body), 12000));
    }

    static String highlights(String place, double lat, double lon) {
        String prompt = "Lokal z Google Maps: " + place
                + ". Wypisz wyłącznie nazwy dań z sekcji Menu Highlights, dokładnie jak na karcie, w oryginalnym języku. "
                + "JSON {\"names\":[\"Dumpling Soup\",\"Pho\"]}. Max 8. Bez tłumaczenia i bez markdown.";
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":"
                + JSONObject.quote(prompt)
                + "}]}],\"tools\":[{\"googleMaps\":{}}],"
                // bez współrzędnych w linku nie podajemy lokalizacji (wcześniej domyślnie był Berlin)
                + (Double.isNaN(lat) || Double.isNaN(lon) ? "" : "\"toolConfig\":{\"retrievalConfig\":{\"latLng\":{\"latitude\":"
                + lat + ",\"longitude\":" + lon + "}}},")
                + "\"generationConfig\":{\"temperature\":0" + THINK + "}}";
        fail.set(null);
        String text = post("gemini-3-flash-preview", bytes(body), 8000);
        if (text == null || !text.contains("names")) return "";
        try {
            JSONArray names = new JSONObject(text).optJSONArray("names");
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

    static String askSearch(String prompt) {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":"
                + JSONObject.quote(prompt)
                + "}]}],\"tools\":[{\"google_search\":{}}],\"generationConfig\":{\"temperature\":0.3" + THINK + "}}";
        String text = tryModels(order(), bytes(body), 10000);
        return text != null ? text : askText(prompt);
    }

    private static String mediaBody(String mime, String b64, String prompt) {
        return "{\"contents\":[{\"role\":\"user\",\"parts\":["
                + "{\"inline_data\":{\"mime_type\":\"" + mime + "\",\"data\":\"" + b64 + "\"}},"
                + "{\"text\":" + JSONObject.quote(prompt) + "}"
                + "]}]," + generation("") + "}";
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static int readMs() {
        return "flash".equals(tier) ? 22000 : 12000;
    }

    private static String orEmpty(String text) {
        return text != null ? text : "{\"items\":[]}";
    }

    private static String tryModels(String[] models, byte[] body, int readMs) {
        return tryModels(models, body, readMs, true);
    }

    private static String tryModels(String[] models, byte[] body, int readMs, boolean notify) {
        long end = System.currentTimeMillis() + DEADLINE_MS;
        fail.set(null);
        for (String model : models) {
            long left = end - System.currentTimeMillis();
            if (left < 2000) {
                fail.set("timeout");
                break;
            }
            String text = post(model, body, (int) Math.min(readMs, left));
            if (text != null) return text;
            String why = fail.get();
            // bez sieci albo z odrzuconym kluczem inne modele też nie odpowiedzą
            if ("offline".equals(why) || "key".equals(why)) break;
        }
        if (notify) report(fail.get());
        return null;
    }

    private static void report(String why) {
        Consumer<String> listener = onFail;
        if (listener != null && why != null && !"bad".equals(why)) listener.accept(why);
    }

    private static String post(String model, byte[] body, int readMs) {
        Request.Builder req = new Request.Builder()
                .url(BASE + model + ":generateContent")
                .header("x-goog-api-key", KEY)
                .post(RequestBody.create(body, JSON_TYPE));
        if (!AppKey.pkg.isEmpty() && !AppKey.cert.isEmpty()) {
            req.header("X-Android-Package", AppKey.pkg).header("X-Android-Cert", AppKey.cert);
        }
        OkHttpClient client = HTTP.newBuilder()
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .callTimeout(readMs + 5000L, TimeUnit.MILLISECONDS)
                .build();
        try (Response res = client.newCall(req.build()).execute()) {
            int code = res.code();
            if (code < 200 || code >= 300) {
                fail.set(code == 429 ? "quota" : code == 401 || code == 403 ? "key" : code >= 500 ? "server" : "bad");
                return null;
            }
            String raw = res.body() == null ? "" : res.body().string();
            JSONArray parts = new JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.optBoolean("thought", false)) continue;
                if (part.has("text")) all.append(part.getString("text"));
            }
            String text = all.toString().trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            new JSONObject(text);
            return text;
        } catch (UnknownHostException | ConnectException e) {
            fail.set("offline");
        } catch (InterruptedIOException e) {
            fail.set("timeout");
        } catch (Exception e) {
            fail.set("bad");
        }
        return null;
    }
}
