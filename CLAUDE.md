# Mealquo – aplikacja Android (liczenie kalorii i makro)

Osobny projekt, niezwiązany z ERP Interlook. Szczegóły w `README.md`.

## Struktura
- `android/` – projekt Androida. WebView z logiką w JavaScript plus natywne moduły: kamera, Gemini, Google Places, mowa, GPS, przypomnienia, Health Connect.
  - UI: `android/app/src/main/assets/www/app.js` i `styles.css` (tu zmienia się wygląd i zachowanie).
  - Natywne: `android/app/src/main/java/app/mealquo/` (`MainActivity.java` – mostek `MealquoNative`).
- `web/` – ten sam interfejs do podglądu w przeglądarce (`web.js`, `ios.css`); `app.js` i `styles.css` trzymaj w zgodzie z `android/.../assets/www/`.

## iPhone (PWA na Cloudflare)
- `web/` to aplikacja dla iPhone'a: `app.js` + `web.js` (zastępuje mostek `MealquoNative`), `sw.js` (offline, zdjęcia posiłków w Cache pod `photos/…`), `manifest.webmanifest`. Instalacja z Safari: Udostępnij → Dodaj do ekranu początkowego.
- `cloudflare/`: Worker (`src/worker.js`) serwuje `../web` i API: `/api/ai` (Gemini, klucz w sekrecie `GEMINI_KEY`), `/api/recognize`, `/api/journal` (KV `JOURNAL`). Limit 30 zapytań/min na IP, tylko z własnego Origin.
- Lokalnie: `cd cloudflare && npm install && npx wrangler dev` (klucz w `cloudflare/.dev.vars`, poza gitem). Wdrożenie: `npx wrangler deploy` (token `CLOUDFLARE_API_TOKEN`). Wrangler 4.86, bo serwer ma Node 20 (nowsze wymagają 22).

## Budowanie
`./build.sh` buduje APK release z R8, podpisane `~/.android/debug.keystore` (Java 17, Gradle 9.9 z `~/gradle-9.9.0`, AGP 9.5 z wbudowanym Kotlinem, SDK w `~/AndroidSDK`; `dl.google.com` działa z tego serwera tylko przez IPv4, stąd `preferIPv4Stack` w `gradle.properties`) i zapisuje go w `~/backups/mealquo-RRRRMMDD_GGMM/mealquo-<wersja>.apk`. Gotowe APK przekaż użytkownikowi jako plik. Nie używaj dołączonego `gradlew`.

Przy nowej wersji podnieś `versionCode` i `versionName` w `android/app/build.gradle`.

## GitHub (publiczne repo `looki123/mealquo`)
- Kod: https://github.com/looki123/mealquo. Commituj i wypychaj (`git push`).
- Wydanie z APK: podnieś wersję, zacommituj, potem `./build.sh --publish`. Tworzy tag `v<wersja>` i release z `mealquo-<wersja>.apk`.
- Najnowsze APK dla użytkowników: https://github.com/looki123/mealquo/releases/latest
- Repo jest publiczne: nigdy nie commituj `local.properties`, kluczy ani APK.

## Klucze API
Klucze Gemini i Google Maps/Places są w `android/local.properties` (`GEMINI_KEY`, `MAPS_KEY`), poza gitem, i trafiają do kodu przez `BuildConfig`. Nigdy nie wpisuj ich w kod ani nie commituj. Według README warto je wymienić w konsoli Google, bo były w testowym APK.

## Stan
- Dane użytkownika w `localStorage` WebView (`talerz`). Zdjęcia posiłków są plikami w `files/photos` (`MealquoNative.savePhoto`), w stanie tylko adres `file://`; localStorage mieści kilka MB.
- Metody mostka `MealquoNative` muszą mieć `@JavascriptInterface` (R8 zachowuje tylko je, `proguard-rules.pro`).
- `targetSdk 36`: aplikacja rysuje się pod paskami systemowymi; marginesy na paski i klawiaturę daje `Bars.fit()` (kontener WebView), wstecz przez `OnBackInvokedCallback`.
- Błędy (awarie, JS, AI) trafiają do `files/errors.log`; użytkownik wysyła je z profilu.
- Subskrypcja 29 zł wyłączona (`locked()` w `app.js` zwraca `false`); kod płatności (Billing 6.2.1) jest w `MainActivity.java`.

## Ograniczenie kluczy do aplikacji
Zapytania do Gemini i Places wysyłają `X-Android-Package` i `X-Android-Cert` (`AppKey.java`; w `app.js` przez `MealquoNative.keyHeaders()`), więc klucze można w konsoli Google ograniczyć do aplikacji Android `app.mealquo` + SHA-1 `59:BC:EB:0F:08:F2:27:62:55:6F:D7:C1:AF:29:9C:0E:9E:FD:8F:F4` (`~/.android/debug.keystore`). Nie zmieniaj ani nie zgub tego keystore: inny podpis oznacza, że klucze przestaną działać, a użytkownicy nie zaktualizują aplikacji.
