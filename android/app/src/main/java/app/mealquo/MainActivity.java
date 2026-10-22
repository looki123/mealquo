package app.mealquo;

import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.health.connect.client.PermissionController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> filePath;
    private Uri photoUri;
    private boolean pendingCamera;
    private static final int FILE = 1001;
    private static final int GOOGLE = 2002;
    private static final int CAM_PERM = 3001;
    private static final int CAMERA = 3002;
    private static final int GALLERY = 3003;
    private static final int SPEECH = 3004;
    private static final int LOC_PERM = 3005;
    private static final int HERE_PERM = 3006;
    private boolean pendingHere;
    private TextToSpeech tts;
    private String speakText = "";
    private boolean pendingSpeech;
    private boolean voiceFallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private BillingClient billing;
    private ProductDetails monthly;

    public class Bridge {
        // klucz Gemini z BuildConfig (local.properties), zamiast wpisanego w app.js
        @JavascriptInterface
        public String geminiKey() {
            return BuildConfig.GEMINI_KEY;
        }

        @JavascriptInterface
        public void setLang(String code) {
            if (code == null) return;
            if (code.equals("uk") || code.equals("en") || code.equals("pl")) Gemini.lang = code;
        }

        @JavascriptInterface
        public void setModel(String tier) {
            Gemini.tier = "flash".equals(tier) ? "flash" : "lite";
        }

        @JavascriptInterface
        public void refreshSub() {
            runOnUiThread(() -> MainActivity.this.refreshSub());
        }

        @JavascriptInterface
        public void subscribe() {
            runOnUiThread(() -> MainActivity.this.subscribe());
        }

        @JavascriptInterface
        public void recipeFor(String id, String title, int kcal) {
            final String rid = id == null ? "" : id;
            final String name = title == null ? "" : title.trim();
            new Thread(() -> {
                recipeProgress(rid, 30, step("Piszę składniki…", "Пишу складники…", "Writing the ingredients…"));
                String lang = "uk".equals(Gemini.lang) ? "українською" : "en".equals(Gemini.lang) ? "in English" : "po polsku";
                String prompt = "Napisz krótki przepis domowy na: " + name + ". Porcja około " + kcal + " kcal. Język: " + lang + ". "
                        + "Zwróć wyłącznie JSON {\"intro\":\"jedno zdanie\",\"minutes\":25,\"ingredients\":[\"200 g ryby\"],\"steps\":[\"Rozgrzej patelnię.\"]}. "
                        + "4 do 8 składników z gramaturą. 4 do 7 krótkich kroków. Bez markdown.";
                String json = Gemini.askText(prompt);
                recipeProgress(rid, 90, step("Składam kroki…", "Складаю кроки…", "Putting the steps together…"));
                if (json == null || !json.contains("steps")) json = "{\"intro\":\"\",\"minutes\":20,\"ingredients\":[],\"steps\":[]}";
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> runJs("window.onRecipe && window.onRecipe("
                        + org.json.JSONObject.quote(rid) + ",'" + packed + "')"));
            }).start();
        }

        @JavascriptInterface
        public void speak(String text) {
            final String said = text == null ? "" : text;
            runOnUiThread(() -> startSpeak(said));
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
                runJs("window.onSpeakEnd && window.onSpeakEnd()");
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || !url.startsWith("https://")) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void pickGoogle() {
            runOnUiThread(() -> {
                Intent i = AccountManager.newChooseAccountIntent(
                        null, null, new String[]{"com.google"},
                        false, null, null, null, null);
                startActivityForResult(i, GOOGLE);
            });
        }

        @JavascriptInterface
        public void pushJournal(String json) {
            final String body = json == null ? "" : json;
            if (body.isEmpty() || Journal.saved(MainActivity.this) == null) return;
            new Thread(() -> {
                try {
                    Journal.write(MainActivity.this, body);
                    say("window.onSyncStatus && window.onSyncStatus('saved')");
                } catch (Exception e) {
                    say("window.onSyncStatus && window.onSyncStatus('fail')");
                }
            }).start();
        }

        @JavascriptInterface
        public void pullJournal() {
            if (Journal.saved(MainActivity.this) == null) {
                say("window.onSyncStatus && window.onSyncStatus('off')");
                return;
            }
            new Thread(() -> {
                try {
                    String json = Journal.read(MainActivity.this);
                    if (json == null || json.isEmpty()) return;
                    String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    say("window.onJournal && window.onJournal('" + packed + "')");
                } catch (Exception e) {
                    say("window.onSyncStatus && window.onSyncStatus('fail')");
                }
            }).start();
        }

        @JavascriptInterface
        public void createJournal() {
            runOnUiThread(() -> Journal.create(MainActivity.this));
        }

        @JavascriptInterface
        public void openJournal() {
            runOnUiThread(() -> Journal.open(MainActivity.this));
        }

        @JavascriptInterface
        public void readWatch(String day) {
            final String when = (day == null || day.length() < 8) ? today() : day;
            runOnUiThread(() -> startWatch(when));
        }

        @JavascriptInterface
        public void stopListen() {
            runOnUiThread(() -> {
                recCancel = true;
                recOn = false;
                try { if (recMic != null) recMic.stop(); } catch (Exception ignored) {}
                if (live != null) live.stop();
                if (speech != null) speech.cancel();
            });
        }

        @JavascriptInterface
        public void listen() {
            runOnUiThread(() -> startSpeech());
        }

        @JavascriptInterface
        public void lookupFood(String name) {
            final String said = name == null ? "" : name.trim();
            if (said.isEmpty()) return;
            new Thread(() -> {
                String prompt = "Użytkownik wpisał produkt, którego nie ma na liście: " + JSONObjectQuote(said)
                        + ". Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":100,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}."
                        + " Jedna pozycja. Kania albo kania grzyb to grzyb czubajka (Macrolepiota), nie kanapka i nie imię. "
                        + "grams to typowa porcja, kcal p c f dla tych gramów. Bez markdown.";
                String json = Gemini.askText(prompt);
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String q = org.json.JSONObject.quote(said);
                runOnUiThread(() -> {
                    if (web == null) return;
                    web.evaluateJavascript("window.onFoodHit && window.onFoodHit(" + q + ",'" + packed + "')", null);
                });
            }).start();
        }

        @JavascriptInterface
        public void describeMeal(String text) {
            final String said = text == null ? "" : text.trim();
            new Thread(() -> {
                String prompt = "Użytkownik powiedział, co zjadł: " + JSONObjectQuote(said)
                        + ". Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":150,\"kcal\":200,\"p\":10,\"c\":20,\"f\":8}]}."
                        + " Rozbij na produkty. grams, kcal, p, c, f dla podanej porcji. Max 6. Bez markdown.";
                String json = Gemini.askText(prompt);
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> {
                    if (web == null) return;
                    web.evaluateJavascript("window.onAiResult && window.onAiResult('" + packed + "')", null);
                });
            }).start();
        }

        @JavascriptInterface
        public void menuFromLink(String url, int kcal, int protein, int carbs, String diet, String skip) {
            final String link = url == null ? "" : url.trim();
            final String kind = diet == null ? "normal" : diet;
            final String avoid = skip == null ? "" : skip.trim();
            final int gen = ++mapGen;
            mapPacked = "";
            mapPct = 18;
            mapStep = step("Czytam link…", "Читаю посилання…", "Reading the link…");
            pushMap();
            new Thread(() -> {
                mapPct = 46;
                mapStep = step("Biorę zdjęcia z Google Maps…", "Беру фото з Google Maps…", "Taking photos from Google Maps…");
                pushMap();
                java.util.List<Maps.Shot> shots = Maps.photos(link);
                double lat = 52.52;
                double lon = 13.40;
                try {
                    java.util.regex.Matcher coords = java.util.regex.Pattern.compile("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)").matcher(java.net.URLDecoder.decode(link, "UTF-8"));
                    if (coords.find()) {
                        lat = Double.parseDouble(coords.group(1));
                        lon = Double.parseDouble(coords.group(2));
                    }
                } catch (Exception ignored) {
                }
                mapPct = 62;
                mapStep = step("Czytam nazwy z Maps…", "Читаю назви з Maps…", "Reading the Maps names…");
                pushMap();
                String names = Gemini.highlights(link, lat, lon);
                String json = "";
                if (!shots.isEmpty()) {
                    mapPct = 78;
                    mapStep = step("Dopasowuję zdjęcia dań…", "Підганяю фото страв…", "Matching the dish photos…");
                    pushMap();
                    json = names.isEmpty() ? dishesFromShots(shots, avoid) : dishesFromHighlights(shots, names, avoid);
                }
                boolean empty = json == null || !json.contains("\"name\"");
                if (empty) {
                    String prompt = "Link restauracji z Google Maps: " + link
                            + ". Zostało " + kcal + " kcal, białko " + protein + " g, węgle " + carbs + " g. Dieta: " + kind + ". "
                            + "Znajdź tę konkretną restaurację i jej kartę. Zwróć wyłącznie JSON {\"place\":\"nazwa\",\"items\":[{\"name\":\"nazwa z karty\",\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":280,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}."
                            + " name to dokładna nazwa z menu, w języku karty, bez tłumaczenia. name_pl, name_uk i name_en to tłumaczenia."
                            + " Dokładnie 3 dania z tej karty, które mieszczą się w pozostałych kaloriach. Prawdziwe pozycje z menu, nie ogólny typ kuchni."
                            + (avoid.isEmpty() ? "" : " Nie proponuj tych dań: " + avoid + ".")
                            + " Bez markdown.";
                    json = Gemini.askSearch(prompt);
                }
                json = stampPhotos(json, shots);
                if (gen != mapGen) return;
                mapPct = 88;
                mapStep = step("Układam dania…", "Складаю страви…", "Laying out the dishes…");
                pushMap();
                if (json == null || json.isEmpty()) json = "{\"items\":[]}";
                mapPacked = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                mapPct = 100;
                mapStep = step("Gotowe", "Готово", "Done");
                String packed = mapPacked;
                runOnUiThread(() -> {
                    if (web == null) return;
                    web.evaluateJavascript("window.onMapMenu && window.onMapMenu('" + packed + "')", null);
                });
            }).start();
        }

        @JavascriptInterface
        public String mapPulse() {
            return mapGen + "\u001f" + mapPct + "\u001f" + (mapStep == null ? "" : mapStep) + "\u001f" + (mapPacked == null ? "" : mapPacked);
        }

        @JavascriptInterface
        public void whereAmI() {
            findPlaces("");
        }

        @JavascriptInterface
        public String nearPulse() {
            return nearGen + "\u001f" + nearPctLive + "\u001f" + (nearStepLive == null ? "" : nearStepLive) + "\u001f" + (nearPacked == null ? "" : nearPacked);
        }

        @JavascriptInterface
        public void placesNear(double lat, double lon, String skip) {
            final String avoid = skip == null ? "" : skip.trim();
            hereToken++;
            hereSent = true;
            final int gen = ++nearGen;
            nearPacked = "";
            new Thread(() -> searchPlaces(gen, lat, lon, avoid)).start();
        }

        @JavascriptInterface
        public void dishPhoto(String key, String query) {
            final String name = key == null ? "" : key;
            final String q = query == null || query.isEmpty() ? name : query;
            new Thread(() -> {
                String url = foodThumb(q);
                runOnUiThread(() -> runJs("window.onDishPhoto && window.onDishPhoto("
                        + org.json.JSONObject.quote(name) + ","
                        + org.json.JSONObject.quote(url == null ? "" : url) + ")"));
            }).start();
        }

        @JavascriptInterface
        public void pickMenuShot(int kcal, int protein, int carbs, String diet) {
            menuKcal = kcal;
            menuProtein = protein;
            menuCarbs = carbs;
            menuDiet = diet == null ? "normal" : diet;
            menuShot = true;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Karta"), GALLERY);
            });
        }

        @JavascriptInterface
        public void readMenu(String image, int kcal, int protein, int carbs, String diet, String skip) {
            startMenuRead(image, kcal, protein, carbs, diet, skip);
        }

        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(() -> openCamera());
        }

        @JavascriptInterface
        public void refine(String comment) {
            final String note = comment == null ? "" : comment;
            new Thread(() -> {
                if (lastB64 == null) return;
                String json = Gemini.ask(lastB64, Gemini.platePrompt(note));
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.onAiResult && window.onAiResult('" + packed + "')", null));
            }).start();
        }

        @JavascriptInterface
        public void rescan(String id, String comment, String image) {
            final String mealId = id == null ? "" : id;
            final String note = comment == null ? "" : comment;
            String raw = image == null ? "" : image;
            int comma = raw.indexOf(',');
            if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
            final String b64 = raw;
            new Thread(() -> {
                String use = b64.isEmpty() ? lastB64 : b64;
                String json = (use == null || use.isEmpty())
                        ? Gemini.askText("Użytkownik poprawia danie: " + note
                        + ". Zwróć wyłącznie JSON {\"reason\":\"\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"count\":1,\"grams\":150,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}. "
                        + "Dla kiści winogron nie podawaj count 1.")
                        : Gemini.ask(use, Gemini.platePrompt(note));
                if (json == null || json.isEmpty()) json = "{\"items\":[]}";
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> runJs("window.onMealFix && window.onMealFix("
                        + org.json.JSONObject.quote(mealId) + ","
                        + org.json.JSONObject.quote(packed) + ")"));
            }).start();
        }

        @JavascriptInterface
        public void suggestExercise(String query) {
            final String q = query == null ? "" : query.trim();
            final int token = ++exToken;
            new Thread(() -> {
                String prompt = "Użytkownik wpisał początek ćwiczenia: " + JSONObjectQuote(q)
                        + ". Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"kcal\":200}]}."
                        + " Do 6 ćwiczeń, które zaczynają się od tych liter albo do nich pasują. kcal za około 30 minut. Bez markdown.";
                String json = Gemini.askText(prompt);
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> {
                    if (web == null || token != exToken) return;
                    web.evaluateJavascript("window.onExerciseHints && window.onExerciseHints(" + token + ",'" + packed + "')", null);
                });
            }).start();
        }

        @JavascriptInterface
        public void suggestTypes(String name) {
            final String n = name == null ? "" : name.trim();
            final int token = ++typeToken;
            new Thread(() -> {
                String prompt = "Wybrane ćwiczenie: " + JSONObjectQuote(n)
                        + ". To dotyczy KAŻDEGO sportu, nie tylko siłowni. Dopytaj o wariant, który realnie zmienia spalanie."
                        + " Zwróć wyłącznie JSON "
                        + "{\"question_pl\":\"Jaki to był trening?\",\"question_uk\":\"Яке саме тренування?\",\"question_en\":\"Which workout was it?\","
                        + "\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"kcal\":220}]}."
                        + " 5 konkretnych wariantów tego ćwiczenia. Przykłady różnic: bieg spokojny/tempo/interwały,"
                        + " rower miasto/szosa/MTB, pływanie kraul/żabka/grzbiet, joga regeneracja/power,"
                        + " boks worek/sparing, taniec spokojny/zumba, narty trasa/pozatrasie,"
                        + " siłownia nogi/klatka/plecy/barki/ramiona/całe ciało."
                        + " kcal za 45 minut albo za 5 km przy 70 kg. Skrajne warianty muszą różnić się o co najmniej 30%."
                        + " Żadne dwie pozycje nie mogą mieć tej samej liczby kcal. Bez markdown.";
                String json = Gemini.askText(prompt);
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> {
                    if (web == null || token != typeToken) return;
                    web.evaluateJavascript("window.onExerciseTypes && window.onExerciseTypes(" + token + ",'" + packed + "')", null);
                });
            }).start();
        }

        @JavascriptInterface
        public void burn(String name, String amount, String unit, String weight) {
            final int token = ++burnToken;
            final String n = name == null ? "" : name;
            final String a = amount == null ? "" : amount;
            final String u = unit == null ? "min" : unit;
            final String w = weight == null ? "70" : weight;
            new Thread(() -> {
                String lang = "po polsku";
                if ("uk".equals(Gemini.lang)) lang = "українською";
                if ("en".equals(Gemini.lang)) lang = "in English";
                String prompt = "Jesteś fizjologiem wysiłku. Policz spalone kilokalorie dla KONKRETNEGO wariantu, nie dla uśrednionego sportu."
                        + " Ćwiczenie i wariant: " + JSONObjectQuote(n)
                        + ". Ilość: " + a + " " + ("km".equals(u) ? "kilometrów" : "minut")
                        + ". Waga: " + w + " kg."
                        + " Zasada dla wszystkich typów: ta sama ilość i inny wariant = inne kcal."
                        + " Bierz pod uwagę partię ciała, tempo, teren, styl, intensywność."
                        + " Siłownia: nogi 7–8 MET, całe ciało 6.5–7.5, plecy 5.5–6.5, klatka 4.8–5.8, barki 4.2–5, ramiona 3.5–4.5."
                        + " Bieg: spokojny ~0.9 kcal/kg/km, zwykły ~1.0, tempo ~1.2, interwały ~1.35."
                        + " Rower: miasto ~0.32, szosa ~0.48, MTB ~0.62 kcal/kg/km. Spinning 7–9 MET."
                        + " Pływanie: żabka mniej niż kraul. Joga regeneracyjna ~2.5 MET, power ~4.5."
                        + " Boks na worku mniej niż sparing. Zumba więcej niż spokojny taniec."
                        + " kcal = MET × waga × minuty / 60 albo kcal na kilogram na kilometr."
                        + " Zwróć wyłącznie JSON {\"kcal\":280,\"note\":\"\"}. note: jedno krótkie zdanie " + lang
                        + " dlaczego TEN wariant spala tyle. Bez markdown.";
                String json = Gemini.askText(prompt);
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> {
                    if (web == null || token != burnToken) return;
                    web.evaluateJavascript("window.onBurn && window.onBurn(" + token + ",'" + packed + "')", null);
                });
            }).start();
        }

        @JavascriptInterface
        public void setReminders(String json) {
            Remind.apply(MainActivity.this, json);
        }

        @JavascriptInterface
        public void askNotify() {
            runOnUiThread(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 3005);
                }
            });
        }

        @JavascriptInterface
        public void foodNote(String id, String name, String kcal, String p, String c, String f, String grams, String count) {
            final String mealId = id == null ? "" : id;
            final String dish = name == null ? "" : name;
            new Thread(() -> {
                String prompt = "Zwróć wyłącznie JSON {\"note\":\"\",\"better\":\"\"}. "
                        + "note: dwa zdania " + Gemini.hintLang() + " o TYM daniu, nie rada na dziś. "
                        + "Pierwsze zdanie: rodzaj, kolor, ile tego widać, gramy. "
                        + "Jeden owoc albo jajko: „Jabłko zielone, 1 sztuka, około 150 g”. "
                        + "Winogrona, orzechy, jagody, chipsy, rodzynki i każda kiść albo garść: NIGDY nie pisz 1 sztuka. "
                        + "Wzorzec: Winogrona czerwone, kiść, około 150 g. "
                        + "Drugie: jedna cecha, na przykład słodycz albo białko. "
                        + "Danie: " + dish + ". Sztuk podane przez system: " + count + ". Jeśli to 1, a danie to kiść, zignoruj 1. Gramów: " + grams + ". "
                        + kcal + " kcal, białko " + p + " g, węglowodany " + c + " g, tłuszcz " + f + " g. "
                        + "Jeśli nazwa nie pasuje do liczb, better to lepsza nazwa. Inaczej better=\"\".";
                String json = Gemini.askText(prompt);
                if (json == null || json.isEmpty()) return;
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                runOnUiThread(() -> runJs("window.onFoodNote && window.onFoodNote("
                        + org.json.JSONObject.quote(mealId) + ","
                        + org.json.JSONObject.quote(packed) + ")"));
            }).start();
        }

        @JavascriptInterface
        public void pickGallery() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Zdjęcie"), GALLERY);
            });
        }
    }

    private int exToken;
    private int typeToken;
    private int burnToken;

    private static String JSONObjectQuote(String s) {
        return org.json.JSONObject.quote(s == null ? "" : s);
    }

    private File plateFile() {
        File dir = new File(getCacheDir(), "plates");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "plate.jpg");
    }

    private boolean pageResumed;
    private boolean menuShot;
    private String menuB64;
    private int menuKcal;
    private int menuProtein;
    private int menuCarbs;
    private String menuDiet = "normal";
    private String lastB64;
    private final java.util.ArrayList<String> pendingJs = new java.util.ArrayList<>();

    private void openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCamera = true;
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAM_PERM);
            return;
        }
        startActivityForResult(new Intent(this, PlateCameraActivity.class), CAMERA);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pageResumed = true;
        if (web != null) web.postDelayed(this::flushJs, 80);
    }

    @Override
    protected void onPause() {
        pageResumed = false;
        super.onPause();
    }

    private void runJs(String js) {
        if (js == null) return;
        if (pageResumed && web != null) {
            web.post(() -> {
                if (web != null) web.evaluateJavascript(js, null);
            });
        } else {
            pendingJs.add(js);
        }
    }

    private void flushJs() {
        if (web == null || pendingJs.isEmpty()) return;
        java.util.ArrayList<String> batch = new java.util.ArrayList<>(pendingJs);
        pendingJs.clear();
        for (String js : batch) web.evaluateJavascript(js, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        web.addJavascriptInterface(new Bridge(), "MealquoNative");
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) this::handleBack);
        }
        web.setHapticFeedbackEnabled(false);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                view.requestFocus();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePath != null) filePath.onReceiveValue(null);
                filePath = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE);
                } catch (Exception e) {
                    filePath = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (granted) {
                    callback.invoke(origin, true, false);
                    return;
                }
                geoCallback = callback;
                geoOrigin = origin;
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOC_PERM);
            }
        });
        web.loadUrl("file:///android_asset/www/index.html");
        startBilling();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void startBilling() {
        billing = BillingClient.newBuilder(this)
                .enablePendingPurchases()
                .setListener((billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) refreshSub();
                })
                .build();
        billing.startConnection(new com.android.billingclient.api.BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    loadProduct();
                    refreshSub();
                }
            }
            @Override
            public void onBillingServiceDisconnected() {}
        });
    }

    private void loadProduct() {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId("mealquo_monthly")
                .setProductType(BillingClient.ProductType.SUBS)
                .build();
        billing.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(java.util.Collections.singletonList(product)).build(),
                (result, list) -> {
                    if (list != null && !list.isEmpty()) monthly = list.get(0);
                });
    }

    private void refreshSub() {
        if (billing == null || !billing.isReady()) return;
        billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (result, purchases) -> {
                    boolean on = false;
                    if (purchases != null) {
                        for (Purchase purchase : purchases) {
                            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED
                                    && purchase.getProducts().contains("mealquo_monthly")) {
                                on = true;
                                if (!purchase.isAcknowledged()) {
                                    billing.acknowledgePurchase(
                                            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build(),
                                            r -> {});
                                }
                            }
                        }
                    }
                    say("window.onSub && window.onSub(" + on + ")");
                });
    }

    private void subscribe() {
        if (billing == null || !billing.isReady() || monthly == null
                || monthly.getSubscriptionOfferDetails() == null
                || monthly.getSubscriptionOfferDetails().isEmpty()) {
            say("window.onSubFail && window.onSubFail()");
            return;
        }
        String token = monthly.getSubscriptionOfferDetails().get(0).getOfferToken();
        BillingFlowParams.ProductDetailsParams params = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(monthly)
                .setOfferToken(token)
                .build();
        billing.launchBillingFlow(this, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(java.util.Collections.singletonList(params))
                .build());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAM_PERM && pendingCamera) {
            pendingCamera = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
            return;
        }
        if (requestCode == SPEECH && pendingSpeech) {
            pendingSpeech = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSpeech();
            else if (web != null) web.evaluateJavascript("window.onVoiceFail && window.onVoiceFail()", null);
            return;
        }
        if (requestCode == LOC_PERM && geoCallback != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
            geoOrigin = null;
            return;
        }
        if (requestCode == HERE_PERM && pendingHere) {
            pendingHere = false;
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) beginLocate(nearGen, pendingSkip == null ? "" : pendingSkip);
            else finishPlaces("{\"items\":[],\"error\":\"location\"}", step("Brak zgody na lokalizację", "Немає дозволу на локацію", "Location permission denied"));
        }
    }

    private int hereToken;
    private volatile boolean hereSent;
    private volatile int nearGen;
    private volatile int nearPctLive;
    private volatile String nearStepLive = "";
    private volatile String nearPacked = "";
    private volatile int mapGen;
    private volatile int mapPct;
    private volatile String mapStep = "";
    private volatile String mapPacked = "";
    private String pendingSkip = "";

    private void findPlaces(String skip) {
        final String avoid = skip == null ? "" : skip.trim();
        pendingSkip = avoid;
        final int gen = ++nearGen;
        nearPacked = "";
        setNear(12, step("Proszę o lokalizację…", "Прошу локацію…", "Asking for location…"));
        runOnUiThread(() -> {
            boolean granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                pendingHere = true;
                setNear(20, step("Pozwól na lokalizację", "Дозволь локацію", "Allow location"));
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, HERE_PERM);
                return;
            }
            beginLocate(gen, avoid);
        });
    }

    private void pushMap() {
        final int pct = mapPct;
        final String label = mapStep == null ? "" : mapStep;
        final String js = "window.onMapProgress && window.onMapProgress(" + pct + ","
                + org.json.JSONObject.quote(label) + ")";
        if (web != null) web.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private void setNear(int pct, String label) {
        nearPctLive = pct;
        nearStepLive = label == null ? "" : label;
        final String js = "window.onNearProgress && window.onNearProgress(" + pct + ","
                + org.json.JSONObject.quote(nearStepLive) + ")";
        if (web != null) web.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private void finishPlaces(String json, String label) {
        if (json == null || json.isEmpty()) json = "{\"items\":[]}";
        setNear(100, label);
        nearPacked = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private Location stayed;

    private Location holdStill(Location fresh) {
        if (fresh == null) return stayed;
        if (stayed != null && stayed.getAccuracy() > 0) {
            int moved = metersBetween(stayed.getLatitude(), stayed.getLongitude(), fresh.getLatitude(), fresh.getLongitude());
            boolean clearer = fresh.getAccuracy() > 0 && stayed.getAccuracy() - fresh.getAccuracy() >= 12 && fresh.getAccuracy() <= 28;
            if (!clearer && moved < 110) return stayed;
            if (!clearer && fresh.getAccuracy() > 30) return stayed;
        }
        if (fresh.getAccuracy() > 0 && fresh.getAccuracy() <= 40) stayed = fresh;
        return fresh;
    }

    private void beginLocate(int gen, String avoid) {
        final int token = ++hereToken;
        hereSent = false;
        setNear(28, step("Łapię dokładny GPS…", "Ловлю точний GPS…", "Getting a precise GPS fix…"));
        runOnUiThread(() -> {
            try {
                FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(this);
                LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                        .setMinUpdateIntervalMillis(500)
                        .setMaxUpdates(8)
                        .setWaitForAccurateLocation(true)
                        .build();
                final Location[] best = new Location[1];
                LocationCallback callback = new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult result) {
                        if (result == null) return;
                        for (Location location : result.getLocations()) {
                            if (location == null || location.getAccuracy() <= 0) continue;
                            if (best[0] == null || location.getAccuracy() < best[0].getAccuracy()) best[0] = location;
                        }
                    }
                };
                fused.requestLocationUpdates(request, callback, Looper.getMainLooper());
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (gen != nearGen) {
                        fused.removeLocationUpdates(callback);
                        return;
                    }
                    fused.removeLocationUpdates(callback);
                    Location use = best[0];
                    if (use == null || use.getAccuracy() <= 0 || use.getAccuracy() > 60) {
                        finishPlaces("{\"items\":[],\"error\":\"location\"}", step(
                                "GPS jest za słaby. Włącz dokładną lokalizację i spróbuj przy oknie.",
                                "GPS занадто слабкий. Увімкни точну локацію і спробуй біля вікна.",
                                "GPS is too weak. Turn on precise location and try by a window."));
                        String packed = nearPacked;
                        runJs("window.onNearby && window.onNearby('" + packed + "')");
                        return;
                    }
                    Location held = holdStill(use);
                    int acc = Math.round(held.getAccuracy());
                    boolean same = stayed != null && held == stayed && use != stayed;
                    String fix = same
                            ? step("Zostaję w tym samym miejscu, ±" + acc + " m", "Залишаюсь на тому самому місці, ±" + acc + " м", "Staying in the same spot, ±" + acc + " m")
                            : step("GPS ±" + acc + " m", "GPS ±" + acc + " м", "GPS ±" + acc + " m");
                    setNear(55, fix);
                    new Thread(() -> searchPlaces(gen, held.getLatitude(), held.getLongitude(), avoid)).start();
                }, 7000);
            } catch (Exception e) {
                if (gen == nearGen) {
                    finishPlaces("{\"items\":[],\"error\":\"location\"}", step("Nie mam lokalizacji", "Немає локації", "No location"));
                    String packed = nearPacked;
                    runJs("window.onNearby && window.onNearby('" + packed + "')");
                }
            }
        });
    }


    private void searchPlaces(int gen, double lat, double lon, String avoid) {
        if (gen != nearGen) return;
        setNear(62, step("Szukam lokali na mapie…", "Шукаю заклади на карті…", "Searching the map…"));
        String json = placesFromMap(lat, lon, avoid);
        String google = placesFromGoogle(lat, lon, avoid);
        json = mergePlaces(json, google);
        if (gen != nearGen) return;
        String here = reverseHere(lat, lon);
        if (json == null || json.isEmpty()) json = "{\"items\":[]}";
        try {
            org.json.JSONObject wrapped = new org.json.JSONObject(json);
            org.json.JSONArray items = wrapped.optJSONArray("items");
            if (items != null && items.length() > 0 && items.getJSONObject(0).optInt("meters", 999) <= 80) {
                String street = streetLine(items.getJSONObject(0).optString("address", ""));
                if (!street.isEmpty()) here = street;
            }
            if (here != null && !here.isEmpty()) wrapped.put("here", here);
            json = wrapped.toString();
        } catch (Exception ignored) {}
        if (gen != nearGen) return;
        finishPlaces(json, step("Gotowe", "Готово", "Done"));
        String packed = nearPacked;
        runOnUiThread(() -> runJs("window.onNearby && window.onNearby('" + packed + "')"));
    }

    private String reverseHere(double lat, double lon) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=pl&lat="
                    + lat + "&lon=" + lon);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mealquo/12.2 nearby");
            if (conn.getResponseCode() != 200) return "";
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            org.json.JSONObject addr = new org.json.JSONObject(raw.toString("UTF-8")).optJSONObject("address");
            if (addr == null) return "";
            String road = addr.optString("road", "");
            if (road.isEmpty()) road = addr.optString("square", "");
            if (road.isEmpty()) road = addr.optString("pedestrian", "");
            String house = addr.optString("house_number", "");
            String line = (road + " " + house).trim();
            return line.isEmpty() ? "" : line;
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String step(String pl, String uk, String en) {
        if ("uk".equals(Gemini.lang)) return uk;
        if ("en".equals(Gemini.lang)) return en;
        return pl;
    }

    private void recipeProgress(String id, int pct, String label) {
        runOnUiThread(() -> runJs("window.onRecipeProgress && window.onRecipeProgress("
                + org.json.JSONObject.quote(id) + "," + pct + ","
                + org.json.JSONObject.quote(label == null ? "" : label) + ")"));
    }

    private void startSpeak(String text) {
        speakText = text == null ? "" : text;
        if (speakText.isEmpty()) return;
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) sayNow();
                else runJs("window.onSpeakEnd && window.onSpeakEnd()");
            });
            return;
        }
        sayNow();
    }

    private void sayNow() {
        if (tts == null || speakText.isEmpty()) return;
        java.util.Locale locale = "uk".equals(Gemini.lang) ? new java.util.Locale("uk")
                : "en".equals(Gemini.lang) ? java.util.Locale.ENGLISH : new java.util.Locale("pl");
        int ready = tts.setLanguage(locale);
        if (ready == TextToSpeech.LANG_MISSING_DATA || ready == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(java.util.Locale.ENGLISH);
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                runOnUiThread(() -> runJs("window.onSpeakProgress && window.onSpeakProgress(2)"));
            }
            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                int pct = speakText.isEmpty() ? 0 : Math.min(100, end * 100 / speakText.length());
                runOnUiThread(() -> runJs("window.onSpeakProgress && window.onSpeakProgress(" + pct + ")"));
            }
            @Override public void onDone(String utteranceId) {
                runOnUiThread(() -> runJs("window.onSpeakEnd && window.onSpeakEnd()"));
            }
            @Override public void onError(String utteranceId) {
                runOnUiThread(() -> runJs("window.onSpeakEnd && window.onSpeakEnd()"));
            }
        });
        tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "recipe");
    }

    private void plateProgress(int pct, String label) {
        runOnUiThread(() -> runJs("window.onPlateProgress && window.onPlateProgress("
                + pct + "," + org.json.JSONObject.quote(label == null ? "" : label) + ")"));
    }

    private void nearProgress(int pct, String label) {
        setNear(pct, label);
    }

    private double[] ipLocation() {
        double[] hit = ipFrom("https://ipwho.is/", "latitude", "longitude");
        if (hit != null) return hit;
        return ipFrom("https://ipapi.co/json/", "latitude", "longitude");
    }

    private double[] ipFrom(String address, String latKey, String lonKey) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(address);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mealquo/11.9");
            if (conn.getResponseCode() != 200) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            org.json.JSONObject o = new org.json.JSONObject(raw.toString("UTF-8"));
            double lat = o.optDouble(latKey, 0);
            double lon = o.optDouble(lonKey, 0);
            if (lat == 0 || lon == 0) return null;
            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String dishesFromHighlights(java.util.List<Maps.Shot> shots, String names, String avoid) {
        java.util.ArrayList<byte[]> jpegs = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> index = new java.util.ArrayList<>();
        for (int i = 0; i < shots.size(); i++) {
            Maps.Shot shot = shots.get(i);
            if (shot == null || shot.jpeg == null || shot.jpeg.length < 800) continue;
            jpegs.add(shot.jpeg);
            index.add(i);
            if (jpegs.size() == 4) break;
        }
        if (jpegs.isEmpty()) return "{\"items\":[]}";
        String prompt = "Zdjęcia są po kolei od 0. To fotki z Google Maps Menu Highlights. "
                + "Oficjalne nazwy, dokładnie jak na karcie: " + names + ". "
                + "Wybierz do 3 zdjęć, na których widać danie z tej listy. name ma być dokładnie jedną z tych nazw, bez tłumaczenia i bez innej potrawy. "
                + "Pierogi to nie pierś z grilla. Zupa z pierożkami to ta nazwa z listy, która oznacza zupę, nie inne danie. "
                + "JSON {\"items\":[{\"name\":\"Dumpling Soup\",\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":350,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0,\"photo\":0}]}. "
                + (avoid == null || avoid.isEmpty() ? "" : "Pomiń: " + avoid + ". ")
                + "Bez markdown.";
        String text = Gemini.askImages(jpegs, prompt);
        if (text == null || !text.contains("\"name\"")) return "{\"items\":[]}";
        try {
            org.json.JSONObject root = new org.json.JSONObject(text);
            org.json.JSONArray items = root.optJSONArray("items");
            if (items == null) return "{\"items\":[]}";
            org.json.JSONArray kept = new org.json.JSONArray();
            for (int i = 0; i < items.length() && kept.length() < 3; i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                if (name.isEmpty() || !names.toLowerCase().contains(name.toLowerCase())) continue;
                int photo = item.optInt("photo", i);
                if (photo < 0 || photo >= index.size()) photo = Math.min(i, index.size() - 1);
                item.put("photo_url", photoSrc(shots.get(index.get(photo))));
                kept.put(item);
            }
            root.put("items", kept);
            return root.toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        }
    }

    private String photoSrc(Maps.Shot shot) {
        if (shot == null || shot.uri == null) return "";
        return shot.uri;
    }

    private String stampPhotos(String json, java.util.List<Maps.Shot> shots) {
        if (json == null || json.isEmpty() || shots == null || shots.isEmpty()) return json;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray items = root.optJSONArray("items");
            if (items == null) return json;
            int p = 0;
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String have = item.optString("photo_url", "");
                if (have.startsWith("data:") || have.startsWith("http")) continue;
                while (p < shots.size() && photoSrc(shots.get(p)).isEmpty()) p++;
                if (p >= shots.size()) break;
                item.put("photo_url", photoSrc(shots.get(p)));
                p++;
            }
            return root.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private String dishesFromShots(java.util.List<Maps.Shot> shots, String avoid) {
        org.json.JSONArray items = new org.json.JSONArray();
        String prompt = "Na zdjęciu jest jedzenie albo napój. Nazwij wyłącznie to, co widać. Nie używaj nazwy z karty, jeśli jej nie ma na zdjęciu. "
                + "Pierogi, pierożki albo kopytka ze śmietaną to pierogi, nigdy pierś z kurczaka i nigdy pechuga a la plancha. "
                + "Quesadilla to tylko złożona tortilla. Miska ryżu z kurczakiem w sosie to nie quesadilla. "
                + "Burger to bułka z kotletem. Skrzydełka to skrzydełka. "
                + "Jeśli nie ma jedzenia ani napoju, zwróć {\"skip\":true}. "
                + "Inaczej wyłącznie JSON {\"name\":\"\",\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":250,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}. "
                + "Makro muszą pasować do tego, co widać. Pierogi mają sporo węgli, nie 3 g, i nie 37 g białka. Bez markdown.";
        String skip = avoid == null ? "" : avoid.toLowerCase();
        for (Maps.Shot shot : shots) {
            if (items.length() >= 3) break;
            if (shot == null || shot.jpeg == null || shot.jpeg.length < 800) continue;
            String text = Gemini.ask(android.util.Base64.encodeToString(shot.jpeg, android.util.Base64.NO_WRAP), prompt);
            if (text == null || text.contains("\"skip\":true") || text.contains("\"skip\": true")) continue;
            try {
                org.json.JSONObject item = new org.json.JSONObject(text);
                String name = item.optString("name", "").trim();
                if (name.isEmpty()) continue;
                if (!skip.isEmpty() && skip.contains(name.toLowerCase())) continue;
                item.put("photo_url", photoSrc(shot));
                items.put(item);
            } catch (Exception ignored) {
            }
        }
        return "{\"place\":\"\",\"items\":" + items.toString() + "}";
    }

    private String relabelFromPhotos(String json, java.util.List<Maps.Shot> shots) {
        if (json == null || json.isEmpty() || shots == null || shots.isEmpty()) return json;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) return json;
            java.util.ArrayList<byte[]> jpegs = new java.util.ArrayList<>();
            StringBuilder proposed = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                int photo = item == null ? i : item.optInt("photo", i);
                if (photo < 0 || photo >= shots.size()) photo = Math.min(i, shots.size() - 1);
                jpegs.add(shots.get(photo).jpeg);
                if (item != null) item.put("photo", i);
                proposed.append(i).append("=").append(item == null ? "" : item.optString("name", "")).append("; ");
            }
            String prompt = "Każde zdjęcie po kolei to osobna pozycja 0, 1, 2. Ktoś nazwał je tak: " + proposed
                    + "Zostaw nazwę tylko wtedy, gdy na TYM zdjęciu naprawdę to widać. "
                    + "Quesadilla to złożona tortilla z serem, nie miska ryżu. Miska ryżu i kurczak w sosie z limonką to gulasz albo curry z ryżem, nigdy quesadilla. "
                    + "Zwróć wyłącznie JSON {\"items\":[{\"name\":\"\",\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":280,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0,\"photo\":0}]}. "
                    + "name w języku dania, photo to numer na tej liście. Bez markdown.";
            String fixed = Gemini.askImages(jpegs, prompt);
            if (fixed == null || !fixed.contains("\"items\"")) return json;
            org.json.JSONArray next = new org.json.JSONObject(fixed).optJSONArray("items");
            if (next == null) return json;
            for (int i = 0; i < items.length() && i < next.length(); i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                org.json.JSONObject better = next.optJSONObject(i);
                if (item == null || better == null) continue;
                String name = better.optString("name", "");
                if (name.isEmpty()) continue;
                item.put("name", name);
                if (better.has("name_pl")) item.put("name_pl", better.optString("name_pl"));
                if (better.has("name_uk")) item.put("name_uk", better.optString("name_uk"));
                if (better.has("name_en")) item.put("name_en", better.optString("name_en"));
                if (better.optInt("kcal", 0) > 0) {
                    item.put("grams", better.optInt("grams", item.optInt("grams", 250)));
                    item.put("kcal", better.optInt("kcal"));
                    item.put("p", better.optInt("p"));
                    item.put("c", better.optInt("c"));
                    item.put("f", better.optInt("f"));
                }
            }
            return root.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private String attachMapPhotos(String json, java.util.List<Maps.Shot> shots) {
        if (json == null || json.isEmpty()) return "{\"items\":[]}";
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray items = root.optJSONArray("items");
            if (items == null) return json;
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                int photo = item.optInt("photo", -1);
                if (photo < 0 || photo >= shots.size()) photo = Math.min(i, shots.size() - 1);
                if (photo >= 0) item.put("photo_url", shots.get(photo).uri);
            }
            return root.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private String placesFromGoogle(double lat, double lon, String skip) {
        setNear(74, step("Sprawdzam Google Maps…", "Перевіряю Google Maps…", "Checking Google Maps…"));
        java.net.HttpURLConnection conn = null;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("includedTypes", new org.json.JSONArray().put("restaurant").put("bar").put("cafe").put("meal_takeaway"));
            body.put("maxResultCount", 8);
            body.put("rankPreference", "DISTANCE");
            org.json.JSONObject circle = new org.json.JSONObject();
            org.json.JSONObject center = new org.json.JSONObject();
            center.put("latitude", lat);
            center.put("longitude", lon);
            circle.put("center", center);
            circle.put("radius", 500);
            org.json.JSONObject restriction = new org.json.JSONObject();
            restriction.put("circle", circle);
            body.put("locationRestriction", restriction);
            byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.net.URL url = new java.net.URL("https://places.googleapis.com/v1/places:searchNearby");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Goog-Api-Key", Maps.KEY);
            conn.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location");
            conn.setDoOutput(true);
            conn.getOutputStream().write(bytes);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return "{\"items\":[]}";
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            org.json.JSONArray places = new org.json.JSONObject(raw.toString("UTF-8")).optJSONArray("places");
            org.json.JSONArray items = new org.json.JSONArray();
            String skipLow = skip == null ? "" : skip.toLowerCase();
            if (places != null) {
                for (int i = 0; i < places.length(); i++) {
                    org.json.JSONObject place = places.optJSONObject(i);
                    if (place == null) continue;
                    org.json.JSONObject nameObj = place.optJSONObject("displayName");
                    String name = nameObj == null ? "" : nameObj.optString("text", "").trim();
                    if (name.isEmpty() || (!skipLow.isEmpty() && skipLow.contains(name.toLowerCase()))) continue;
                    org.json.JSONObject loc = place.optJSONObject("location");
                    double plat = loc == null ? lat : loc.optDouble("latitude", lat);
                    double plon = loc == null ? lon : loc.optDouble("longitude", lon);
                    int meters = metersBetween(lat, lon, plat, plon);
                    org.json.JSONObject row = new org.json.JSONObject();
                    row.put("name", name);
                    row.put("address", place.optString("formattedAddress", ""));
                    row.put("meters", meters);
                    row.put("maps", "https://www.google.com/maps/search/?api=1&query="
                            + java.net.URLEncoder.encode(name + " " + plat + "," + plon, "UTF-8"));
                    items.put(row);
                    if (items.length() == 5) break;
                }
            }
            org.json.JSONObject out = new org.json.JSONObject();
            out.put("items", items);
            return out.toString();
        } catch (Exception e) {
            return "{\"items\":[]}";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String mergePlaces(String osm, String google) {
        try {
            org.json.JSONArray items = new org.json.JSONArray();
            java.util.ArrayList<org.json.JSONObject> rows = new java.util.ArrayList<>();
            java.util.ArrayList<org.json.JSONObject> kept = new java.util.ArrayList<>();
            addPlaceRows(rows, google);
            addPlaceRows(rows, osm);
            rows.sort((a, b) -> a.optInt("meters", 9999) - b.optInt("meters", 9999));
            for (org.json.JSONObject row : rows) {
                if (row.optInt("meters", 9999) > 500) continue;
                org.json.JSONObject twin = null;
                for (org.json.JSONObject have : kept) {
                    if (samePlace(have, row)) { twin = have; break; }
                }
                if (twin == null) kept.add(row);
                else foldPlace(twin, row);
            }
            for (org.json.JSONObject row : kept) {
                items.put(row);
                if (items.length() == 3) break;
            }
            if (items.length() == 0) return osm;
            org.json.JSONObject out = new org.json.JSONObject();
            out.put("items", items);
            return out.toString();
        } catch (Exception e) {
            return osm;
        }
    }

    private void foldPlace(org.json.JSONObject keep, org.json.JSONObject extra) throws Exception {
        String name = extra.optString("name", "");
        if (name.length() > keep.optString("name", "").length()) keep.put("name", name);
        if (extra.optInt("meters", 9999) < keep.optInt("meters", 9999)) keep.put("meters", extra.optInt("meters"));
        String addr = extra.optString("address", "");
        if (!addr.isEmpty() && (keep.optString("address", "").isEmpty() || addr.length() < keep.optString("address", "").length())) {
            keep.put("address", addr);
        }
    }

    private boolean samePlace(org.json.JSONObject a, org.json.JSONObject b) {
        String na = coreName(a.optString("name", ""));
        String nb = coreName(b.optString("name", ""));
        if (na.isEmpty() || nb.isEmpty()) return false;
        boolean nameSame = na.equals(nb) || na.startsWith(nb) || nb.startsWith(na);
        if (!nameSame) return false;
        String da = door(a.optString("address", ""));
        String db = door(b.optString("address", ""));
        if (!da.isEmpty() && da.equals(db)) return true;
        return Math.abs(a.optInt("meters", 0) - b.optInt("meters", 0)) <= 40;
    }

    private String coreName(String name) {
        String n = name.toLowerCase()
                .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
        n = n.replaceAll("\\b(bar|restaurant|cafe|café|pub|lounge|kitchen|the|am|im)\\b", " ");
        return n.replaceAll("[^a-z0-9]", "");
    }

    private String door(String address) {
        String a = address.toLowerCase()
                .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
                .replace("straße", "str").replace("strasse", "str").replace("str.", "str");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-z]{4,}).{0,12}?(\\d+)").matcher(a);
        return m.find() ? m.group(1) + m.group(2) : "";
    }

    private String streetLine(String address) {
        if (address == null || address.isEmpty()) return "";
        int comma = address.indexOf(',');
        return (comma > 0 ? address.substring(0, comma) : address).trim();
    }

    private void addPlaceRows(java.util.ArrayList<org.json.JSONObject> rows, String json) {
        if (json == null || json.isEmpty()) return;
        try {
            org.json.JSONArray items = new org.json.JSONObject(json).optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject row = items.optJSONObject(i);
                if (row == null || row.optString("name", "").trim().isEmpty()) continue;
                rows.add(row);
            }
        } catch (Exception ignored) {}
    }

    private String placesFromMap(double lat, double lon, String skip) {
        int[] radii = {250, 500};
        for (int radius : radii) {
            String found = overpass(lat, lon, radius, skip);
            if (found != null) return found;
        }
        return null;
    }

    private String overpass(double lat, double lon, int radius, String skip) {
        java.net.HttpURLConnection conn = null;
        try {
            String query = "[out:json][timeout:8];(node[\"amenity\"~\"restaurant|cafe|fast_food|bar|food_court\"](around:"
                    + radius + "," + lat + "," + lon + ");way[\"amenity\"~\"restaurant|cafe|fast_food|bar|food_court\"](around:"
                    + radius + "," + lat + "," + lon + "););out center 24;";
            java.net.URL url = new java.net.URL("https://overpass-api.de/api/interpreter?data="
                    + java.net.URLEncoder.encode(query, "UTF-8"));
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mealquo/11.6 nearby restaurants");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            org.json.JSONArray elements = new org.json.JSONObject(raw.toString("UTF-8")).optJSONArray("elements");
            if (elements == null) return null;
            String skipLow = skip == null ? "" : skip.toLowerCase();
            java.util.ArrayList<org.json.JSONObject> rows = new java.util.ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                org.json.JSONObject el = elements.getJSONObject(i);
                org.json.JSONObject tags = el.optJSONObject("tags");
                if (tags == null || !tags.has("name")) continue;
                String name = tags.optString("name", "").trim();
                if (name.isEmpty() || (!skipLow.isEmpty() && skipLow.contains(name.toLowerCase()))) continue;
                double plat = el.has("lat") ? el.optDouble("lat") : el.optDouble("center.lat", el.optJSONObject("center") == null ? 0 : el.getJSONObject("center").optDouble("lat"));
                org.json.JSONObject center = el.optJSONObject("center");
                if (center != null) {
                    plat = center.optDouble("lat");
                }
                double plon = el.has("lon") ? el.optDouble("lon") : 0;
                if (center != null) plon = center.optDouble("lon");
                if (plat == 0 || plon == 0) continue;
                int meters = metersBetween(lat, lon, plat, plon);
                if (meters > 550) continue;
                String street = tags.optString("addr:street", "");
                String house = tags.optString("addr:housenumber", "");
                String city = tags.optString("addr:city", "");
                String address = (street + " " + house).trim();
                if (!city.isEmpty()) address = address.isEmpty() ? city : address + ", " + city;
                org.json.JSONObject row = new org.json.JSONObject();
                row.put("name", name);
                row.put("address", address);
                row.put("meters", meters);
                row.put("maps", "https://www.google.com/maps/search/?api=1&query="
                        + java.net.URLEncoder.encode(name + " " + plat + "," + plon, "UTF-8"));
                row.put("_m", meters);
                rows.add(row);
            }
            if (rows.isEmpty()) return null;
            rows.sort((a, b) -> a.optInt("_m") - b.optInt("_m"));
            org.json.JSONArray items = new org.json.JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (org.json.JSONObject row : rows) {
                String key = row.optString("name").toLowerCase();
                if (!seen.add(key)) continue;
                row.remove("_m");
                items.put(row);
                if (items.length() == 3) break;
            }
            if (items.length() == 0) return null;
            org.json.JSONObject out = new org.json.JSONObject();
            out.put("items", items);
            return out.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private int metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private String foodThumb(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return "";
        String meal = mealDbThumb(q);
        if (meal != null && !meal.isEmpty()) return meal;
        String photo = openverseThumb(q);
        return photo == null ? "" : photo;
    }

    private String mealDbThumb(String query) {
        String[] parts = query.toLowerCase().split("[^a-zA-Záéíóúñü]+");
        java.util.ArrayList<String> words = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.length() <= 3) continue;
            if (p.equals("with") || p.equals("and") || p.equals("the") || p.equals("vegetables") || p.equals("vegetable")
                    || p.equals("salad") || p.equals("rice") || p.equals("buckwheat") || p.equals("roasted")
                    || p.equals("herbs") || p.equals("soup") || p.equals("toast") || p.equals("yogurt")
                    || p.equals("food") || p.equals("dish") || p.equals("from")) continue;
            if (p.equals("prawns") || p.equals("prawn")) p = "shrimp";
            if (p.equals("lamb")) p = "lamb";
            words.add(p);
        }
        if (words.isEmpty()) return "";
        for (String word : words) {
            String hit = mealDbSearch(word);
            if (!hit.isEmpty()) return hit;
        }
        return "";
    }

    private String mealDbSearch(String word) {
        try {
            String body = httpGet("https://www.themealdb.com/api/json/v1/1/search.php?s=" + java.net.URLEncoder.encode(word, "UTF-8"));
            if (body.isEmpty()) return "";
            org.json.JSONArray meals = new org.json.JSONObject(body).optJSONArray("meals");
            if (meals == null || meals.length() == 0) return "";
            String thumb = meals.getJSONObject(0).optString("strMealThumb", "");
            return thumb.startsWith("http") ? thumb : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String openverseThumb(String query) {
        try {
            String body = httpGet("https://api.openverse.org/v1/images/?page_size=8&q="
                    + java.net.URLEncoder.encode(query + " food dish", "UTF-8"));
            if (body.isEmpty()) return "";
            org.json.JSONArray results = new org.json.JSONObject(body).optJSONArray("results");
            if (results == null) return "";
            String best = "";
            int bestScore = -1;
            String low = query.toLowerCase();
            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject row = results.getJSONObject(i);
                String url = row.optString("url", "");
                if (!url.startsWith("http")) continue;
                String title = row.optString("title", "").toLowerCase();
                int score = 0;
                for (String w : low.split("[^a-zA-Záéíóúñü]+")) {
                    if (w.length() > 3 && title.contains(w)) score += 2;
                }
                if (url.contains("flickr") || url.contains("wikimedia") || url.contains("static")) score += 1;
                if (score > bestScore) { bestScore = score; best = url; }
            }
            return best;
        } catch (Exception e) {
            return "";
        }
    }

    private String httpGet(String address) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(address);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mealquo/12.6");
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return "";
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            return raw.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String wikiThumb(String query) {
        String page = wikiPage(query);
        if (page != null && !page.isEmpty()) return page;
        String shortName = query == null ? "" : query.replaceAll("\\s+with\\s+.*", "").replaceAll("\\s+and\\s+.*", "").trim();
        if (!shortName.isEmpty() && !shortName.equals(query)) {
            page = wikiPage(shortName);
            if (page != null && !page.isEmpty()) return page;
        }
        HttpURLConnection conn = null;
        try {
            String q = java.net.URLEncoder.encode((query == null ? "" : query) + " food", "UTF-8");
            java.net.URL url = new java.net.URL("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch="
                    + q + "&gsrnamespace=6&gsrlimit=4&prop=pageimages&piprop=thumbnail&pithumbsize=320");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mealquo/12.4 (food thumbnails)");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return "";
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            org.json.JSONObject pages = new org.json.JSONObject(raw.toString("UTF-8"))
                    .getJSONObject("query").getJSONObject("pages");
            java.util.Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                org.json.JSONObject hit = pages.getJSONObject(keys.next());
                if (hit.has("thumbnail")) return hit.getJSONObject("thumbnail").optString("source", "");
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    private String wikiPage(String query) {
        if (query == null || query.trim().isEmpty()) return "";
        HttpURLConnection conn = null;
        try {
            String title = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "_");
            java.net.URL url = new java.net.URL("https://en.wikipedia.org/api/rest_v1/page/summary/" + title);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mealquo/12.4");
            if (conn.getResponseCode() != 200) return "";
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
            org.json.JSONObject thumb = new org.json.JSONObject(raw.toString("UTF-8")).optJSONObject("thumbnail");
            return thumb == null ? "" : thumb.optString("source", "");
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void startMenuRead(String image, int kcal, int protein, int carbs, String diet, String skip) {
        String raw = image == null ? "" : image;
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
        if (raw.isEmpty() && menuB64 != null) raw = menuB64;
        if (!raw.isEmpty()) menuB64 = raw;
        final String b64 = raw;
        final String kind = diet == null || diet.isEmpty() ? (menuDiet == null ? "normal" : menuDiet) : diet;
        final String avoid = skip == null ? "" : skip.trim();
        final int useKcal = kcal > 0 ? kcal : menuKcal;
        final int useP = protein > 0 ? protein : menuProtein;
        final int useC = carbs > 0 ? carbs : menuCarbs;
        new Thread(() -> {
            String prompt = "To jest zrzut ekranu karty restauracji albo listy dań."
                    + " Zostało " + useKcal + " kcal, białko " + useP + " g, węgle " + useC + " g. Dieta: " + kind + "."
                    + " Wybierz dokładnie 3 pozycje WIDOCZNE na zrzucie, które mieszczą się w pozostałych kaloriach."
                    + " Nie wymyślaj dań spoza zdjęcia."
                    + (avoid.isEmpty() ? "" : " Nie proponuj: " + avoid + ".")
                    + " Zwróć wyłącznie JSON {\"place\":\"\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":280,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}.";
            String json = b64.isEmpty() ? "{\"items\":[]}" : Gemini.ask(b64, prompt);
            if (json == null || json.isEmpty()) json = "{\"items\":[]}";
            String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            runOnUiThread(() -> runJs("window.onMenuShot && window.onMenuShot('" + packed + "')"));
        }).start();
    }

    private void deliverBitmap(Bitmap bmp) {
        if (bmp == null) return;
        int max = 1280;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w > max || h > max) {
            float scale = Math.min(max / (float) w, max / (float) h);
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, os);
        final String b64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
        lastB64 = b64;
        runJs("window.onNativePhoto && window.onNativePhoto(" + org.json.JSONObject.quote("data:image/jpeg;base64," + b64) + ")");
        plateProgress(28, step("Zdjęcie zapisane", "Фото збережено", "Photo saved"));
        new Thread(() -> {
            plateProgress(46, step("Rozpoznaję danie…", "Розпізнаю страву…", "Reading the dish…"));
            String json = Gemini.ask(b64, Gemini.platePrompt(""));
            plateProgress(88, step("Liczę makro…", "Рахую макро…", "Counting macros…"));
            pushAi(json);
            plateProgress(100, step("Gotowe", "Готово", "Done"));
        }).start();
    }

    private void deliverReady(Bitmap bmp, String json) {
        String b64 = "";
        if (bmp != null) {
            int max = 960;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w > max || h > max) {
                float scale = Math.min(max / (float) w, max / (float) h);
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
            }
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, os);
            b64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            lastB64 = b64;
        }
        if (json == null || !json.contains("items")) json = "{\"items\":[]}";
        String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String dataUrl = b64.isEmpty() ? "" : "data:image/jpeg;base64," + b64;
        runJs("window.onPlateReady && window.onPlateReady("
                + org.json.JSONObject.quote(dataUrl) + ","
                + org.json.JSONObject.quote(packed) + ")");
    }

    private void pushAi(String json) {
        String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        runOnUiThread(() -> runJs("window.onAiResult && window.onAiResult('" + packed + "')"));
    }

    private Bitmap decodeUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE) {
            if (filePath == null) return;
            filePath.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePath = null;
            return;
        }
        if (requestCode == GOOGLE && resultCode == RESULT_OK && data != null) {
            String email = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (email == null) email = "";
            final String safe = email.replace("\\", "\\\\").replace("'", "\\'");
            web.evaluateJavascript("window.onGoogleAccount && window.onGoogleAccount('" + safe + "')", null);
            return;
        }
        if ((requestCode == Journal.CREATE || requestCode == Journal.OPEN) && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Journal.remember(this, data.getData());
            String mode = requestCode == Journal.OPEN ? "open" : "new";
            web.evaluateJavascript("window.onSyncLinked && window.onSyncLinked('" + mode + "')", null);
            return;
        }
        if (requestCode == 2103) {
            if (pendingWatchDay != null && Watch.INSTANCE.granted(this)) loadWatch(pendingWatchDay);
            else say("window.onWatchStatus && window.onWatchStatus('denied')");
            return;
        }
        if (requestCode == SPEECH && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String text = said != null && !said.isEmpty() ? said.get(0) : "";
            web.evaluateJavascript("window.onVoice && window.onVoice(" + org.json.JSONObject.quote(text) + ")", null);
            return;
        }
        if (requestCode == CAMERA && resultCode == RESULT_OK) {
            String json = data != null ? data.getStringExtra("json") : null;
            Bitmap bmp = BitmapFactory.decodeFile(plateFile().getAbsolutePath());
            if (json != null && json.contains("items")) deliverReady(bmp, json);
            else deliverBitmap(bmp);
            return;
        }
        if (requestCode == GALLERY) {
            if (menuShot) {
                menuShot = false;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    Bitmap bmp = decodeUri(data.getData());
                    if (bmp == null) {
                        runJs("window.onMenuShot && window.onMenuShot('')");
                        return;
                    }
                    int max = 1280;
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    if (w > max || h > max) {
                        float scale = Math.min(max / (float) w, max / (float) h);
                        bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
                    }
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, os);
                    menuB64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
                    startMenuRead(menuB64, menuKcal, menuProtein, menuCarbs, menuDiet, "");
                } else {
                    runJs("window.onMenuShot && window.onMenuShot('')");
                }
                return;
            }
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                deliverBitmap(decodeUri(data.getData()));
            }
        }
    }

    private String pendingWatchDay;

    private String today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private void startWatch(String day) {
        pendingWatchDay = day;
        String st = Watch.INSTANCE.status(this);
        if (!"ready".equals(st)) {
            say("window.onWatchStatus && window.onWatchStatus(" + org.json.JSONObject.quote(st) + ")");
            if ("install".equals(st)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")));
                } catch (Exception ignored) {}
            }
            return;
        }
        if (!Watch.INSTANCE.granted(this)) {
            Intent intent = PermissionController.createRequestPermissionResultContract().createIntent(this, Watch.INSTANCE.permissions());
            startActivityForResult(intent, 2103);
            return;
        }
        loadWatch(day);
    }

    private void loadWatch(String day) {
        new Thread(() -> {
            try {
                String json = Watch.INSTANCE.read(this, day);
                String packed = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                say("window.onWatch && window.onWatch('" + packed + "')");
            } catch (Exception e) {
                say("window.onWatchStatus && window.onWatchStatus('fail')");
            }
        }).start();
    }
    private LiveSpeech live;
    private SpeechRecognizer speech;
    private AudioRecord recMic;
    private volatile boolean recOn;
    private volatile boolean recCancel;

    private void say(String js) {
        if (web == null) return;
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private void startSpeech() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSpeech = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, SPEECH);
            return;
        }
        if (recOn && speech != null) return;
        recCancel = false;
        recOn = true;
        say("window.onVoiceStart && window.onVoiceStart()");
        new Thread(this::captureSpeech).start();
    }

    private void startRecognizer() {
        if (speech != null) {
            try { speech.destroy(); } catch (Exception ignored) {}
            speech = null;
        }
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle params) {}
            @Override public void onBeginningOfSpeech() {
                say("window.onVoicePartial && window.onVoicePartial(" + org.json.JSONObject.quote(hearing()) + ")");
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                if (recCancel) return;
                boolean hard = error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                        || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
                if (!voiceFallback && hard) {
                    voiceFallback = true;
                    try { if (speech != null) speech.cancel(); } catch (Exception ignored) {}
                    new Thread(MainActivity.this::captureSpeech).start();
                    return;
                }
                recOn = false;
                say("window.onVoiceFail && window.onVoiceFail()");
            }
            @Override public void onResults(android.os.Bundle results) {
                if (recCancel) return;
                recOn = false;
                String text = firstHeard(results);
                if (text.isEmpty()) say("window.onVoiceFail && window.onVoiceFail()");
                else say("window.onVoice && window.onVoice(" + org.json.JSONObject.quote(text) + ")");
            }
            @Override public void onPartialResults(android.os.Bundle partial) {
                if (recCancel) return;
                String text = firstHeard(partial);
                if (!text.isEmpty()) {
                    say("window.onVoicePartial && window.onVoicePartial(" + org.json.JSONObject.quote(text) + ")");
                }
            }
            @Override public void onEvent(int eventType, android.os.Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLocale());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLocale());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        try {
            speech.startListening(intent);
        } catch (Exception e) {
            voiceFallback = true;
            new Thread(this::captureSpeech).start();
        }
    }

    private String voiceLocale() {
        if ("uk".equals(Gemini.lang)) return "uk-UA";
        if ("en".equals(Gemini.lang)) return "en-US";
        return "pl-PL";
    }

    private String hearing() {
        if ("uk".equals(Gemini.lang)) return "Слухаю…";
        if ("en".equals(Gemini.lang)) return "Listening…";
        return "Słucham…";
    }

    private String firstHeard(android.os.Bundle bundle) {
        if (bundle == null) return "";
        java.util.ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0) == null) return "";
        return list.get(0).trim();
    }

    private void captureSpeech() {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min < 3200) min = 3200;
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
        recMic = mic;
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        boolean spoke = false;
        try {
            mic.startRecording();
            byte[] buf = new byte[3200];
            long start = System.currentTimeMillis();
            long last = start;
            while (recOn && System.currentTimeMillis() - start < 8000) {
                int n = mic.read(buf, 0, buf.length);
                if (n <= 0) continue;
                pcm.write(buf, 0, n);
                long energy = 0;
                int samples = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int s = (buf[i] & 0xff) | (buf[i + 1] << 8);
                    energy += Math.abs(s);
                    samples++;
                }
                if (samples > 0 && energy / samples > 180) {
                    spoke = true;
                    last = System.currentTimeMillis();
                }
                if (spoke && System.currentTimeMillis() - last > 800) break;
            }
        } catch (Exception e) {
            recOn = false;
            if (!recCancel) say("window.onVoiceFail && window.onVoiceFail()");
            return;
        } finally {
            recOn = false;
            try { mic.stop(); } catch (Exception ignored) {}
            try { mic.release(); } catch (Exception ignored) {}
            recMic = null;
        }
        if (recCancel || !spoke) {
            if (!recCancel) say("window.onVoiceFail && window.onVoiceFail()");
            return;
        }
        say("window.onVoicePartial && window.onVoicePartial(" + org.json.JSONObject.quote(listeningDone()) + ")");
        String b64 = Base64.encodeToString(wav(pcm.toByteArray(), rate), Base64.NO_WRAP);
        String json = Gemini.askAudio(b64, "Na nagraniu mówi jedna osoba — ta, która zaczęła pierwsza. "
                + "Przepisz wyłącznie jej słowa, " + Gemini.hintLang() + ". "
                + "Pomiń drugą osobę, która włącza się w trakcie albo pod koniec, "
                + "pominięte mają być też radio, telewizor, muzyka, lektor i rozmowy obok. "
                + "Jeśli słowa nie należą do pierwszego głosu, nie wpisuj ich. "
                + "Zwróć wyłącznie JSON {\"text\":\"\"}.");
        String text = "";
        try { text = new org.json.JSONObject(json).optString("text", "").trim(); } catch (Exception ignored) {}
        if (text.isEmpty()) say("window.onVoiceFail && window.onVoiceFail()");
        else say("window.onVoice && window.onVoice(" + org.json.JSONObject.quote(text) + ")");
    }

    private String listeningDone() {
        if ("uk".equals(Gemini.lang)) return "Чую, перевіряю…";
        if ("en".equals(Gemini.lang)) return "Heard you, checking…";
        return "Słyszę, sprawdzam…";
    }

    private byte[] wav(byte[] pcm, int rate) {
        int data = pcm.length;
        byte[] out = new byte[44 + data];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        int size = 36 + data;
        out[4] = (byte) size; out[5] = (byte) (size >> 8); out[6] = (byte) (size >> 16); out[7] = (byte) (size >> 24);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        out[16] = 16; out[17] = 0; out[18] = 0; out[19] = 0;
        out[20] = 1; out[21] = 0;
        out[22] = 1; out[23] = 0;
        out[24] = (byte) rate; out[25] = (byte) (rate >> 8); out[26] = (byte) (rate >> 16); out[27] = (byte) (rate >> 24);
        int byteRate = rate * 2;
        out[28] = (byte) byteRate; out[29] = (byte) (byteRate >> 8); out[30] = (byte) (byteRate >> 16); out[31] = (byte) (byteRate >> 24);
        out[32] = 2; out[33] = 0;
        out[34] = 16; out[35] = 0;
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        out[40] = (byte) data; out[41] = (byte) (data >> 8); out[42] = (byte) (data >> 16); out[43] = (byte) (data >> 24);
        System.arraycopy(pcm, 0, out, 44, data);
        return out;
    }

    private void handleBack() {
        if (web == null) return;
        web.evaluateJavascript("(function(){return window.onAndroidBack ? window.onAndroidBack() : false})()", value -> {
            if ("false".equals(value)) finish();
        });
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (live != null) live.stop();
        if (speech != null) speech.destroy();
        super.onDestroy();
    }
}
