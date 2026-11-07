# Mealquo – aplikacja Android (liczenie kalorii i makro)

Osobny projekt, niezwiązany z ERP Interlook. Szczegóły w `README.md`.

## Struktura
- `android/` – projekt Androida. WebView z logiką w JavaScript plus natywne moduły: kamera, Gemini, Google Places, mowa, GPS, przypomnienia, Health Connect.
  - UI: `android/app/src/main/assets/www/app.js` i `styles.css` (tu zmienia się wygląd i zachowanie).
  - Natywne: `android/app/src/main/java/app/mealquo/` (`MainActivity.java` – mostek `MealquoNative`).
- `web/` – ten sam interfejs do podglądu w przeglądarce (`web.js`, `ios.css`); `app.js` i `styles.css` trzymaj w zgodzie z `android/.../assets/www/`.

## iPhone (PWA na Cloudflare)
- `web/` to aplikacja dla iPhone'a: `app.js` + `web.js` (zastępuje mostek `MealquoNative`), `sw.js` (offline, zdjęcia posiłków w Cache pod `photos/…`), `manifest.webmanifest`. Instalacja z Safari: Udostępnij → Dodaj do ekranu początkowego.
- Drugi silnik: Claude Sonnet 5.5 (`/api/fix` w Workerze, sekret `ANTHROPIC_API_KEY`) robi poprawki talerza po komentarzu i jest zapasem, gdy Gemini nie odpowie. Android woła go przez `Claude.java` z nagłówkiem `X-Mealquo-App` (`MEALQUO_TOKEN` w `local.properties` = sekret `APP_TOKEN` w Cloudflare).
- `cloudflare/`: Worker (`src/worker.js`) serwuje `../web` i API: `/api/ai` (Gemini, klucz w sekrecie `GEMINI_KEY`), `/api/recognize`, `/api/journal` (KV `JOURNAL`). Limit 30 zapytań/min na IP, tylko z własnego Origin.
- Adres: https://mealquo.mealquo.workers.dev (konto Cloudflare lukjaro@gmail.com, KV `JOURNAL` 6e8fe1fc…).
- Przypomnienia na iPhonie: Web Push (iOS 16.4+, tylko aplikacja z ekranu początkowego). `web.js` wysyła subskrypcję i ustawienia z `syncReminders()` na `/api/push` (KV `PUSH`), cron co 5 min (`remindAll`) wysyła je wg `src/remind.js` (te same pory i teksty co `Remind`/`RemindReceiver`). Szyfrowanie i VAPID: `src/push.js` (sekret `VAPID_PRIVATE`, kopia klucza w `~/.config/mealquo/vapid.json`). Testy: `npm test`.
- Logowanie na iPhonie: konto i dziennik są też na serwerze (`/api/journal`), więc „Zaloguj” na nowym telefonie przywraca dane (`cloudAccount`, `enterCloudAccount`).
- Lokalnie: `cd cloudflare && npm install && npx wrangler dev` (klucz w `cloudflare/.dev.vars`, poza gitem). Wdrożenie: `cd cloudflare && npx wrangler deploy`; logowanie OAuth wranglera jest w `~/.config/.wrangler/config/default.toml` (odświeża się samo). Wrangler 4.86, bo serwer ma Node 20 (nowsze wymagają 22).
- API Cloudflare często odpowiada „throttling” (kod 971): ponawiaj co minutę.
- Po zmianie `app.js`/`web.js` podnieś `SHELL` w `web/sw.js` tylko przy zmianie listy plików; pliki i tak idą najpierw z sieci.

## Baza (na czas testów na serwerze ERP)
- Dzienniki kont e-mail (iPhone i Android) są w MySQL, baza `mealquo` na tym VPS (użytkownik `mealquo` z prawami tylko do niej). Usługa `server/` (Node, systemd `mealquo-api`, konto systemowe `mealquo`, `/opt/mealquo-api`, hasła w `/etc/mealquo-api.env`) słucha na 127.0.0.1:8790; nginx wystawia ją jako `https://erp.interlook.pl/mealquo-api/` (blok w `/etc/nginx/sites-enabled/interlook`). Woła ją tylko Worker (`BACKEND_URL`, sekret `BACKEND_TOKEN`).
- API v2 (`server/v2.mjs`, przez Worker `/api/v2/*`): konta na serwerze (scrypt, tokeny sesji w `sessions`), wpisy w `entries` (meal/workout/doc, wygrywa nowszy `updated_at`, kursor `stamp`), eksport, zmiana hasła, usunięcie konta, blokada po 10 złych hasłach. Telefony sprzed v2 przechodzą skrótem hasła (`legacy`, kolumna `legacy_auth`); konta z v1 (`journals`) przenoszą się przy pierwszym logowaniu. Klient: `syncV2()` w `app.js` (sesja w localStorage `mealquo_v2_<email>`). Testy: `sudo bash -c "set -a; . /etc/mealquo-api.env; node server/test/v2.test.mjs"`.
- Aktualizacja usługi: `server/deploy.sh`. Kopia bazy codziennie 3:17 do `~/backups/mealquo-db` (14 dni, `/etc/cron.d/mealquo-db-backup`).
- Przy przejściu na komercję: nowy VPS, `server/deploy.sh` + import kopii bazy, potem zmiana `BACKEND_URL` w `cloudflare/wrangler.jsonc` i usunięcie bloku `mealquo-api` z nginx ERP.
- Android synchronizuje przez `MealquoNative.cloudJournal` (`Server.java`, token `X-Mealquo-App`), iPhone przez `fetch("/api/journal")`; wspólna logika w `cloudJournal()` w `app.js`. Konta Google na Androidzie (bez hasła) nadal tylko przez plik na Dysku.

## Sklepy (przygotowanie)
- Google Play: `./build.sh --aab` – paczka AAB podpisana kluczem przesyłania `~/.android/mealquo-upload.jks` (hasło w `local.properties` i `~/backups/mealquo-upload-haslo.txt`, kopia klucza `~/backups/mealquo-upload.jks.bak`). Play podpisuje wydanie własnym kluczem: jego SHA-1 trzeba dodać do ograniczeń klucza Google. APK na GitHub dalej podpisuje `debug.keystore` (aktualizacje u obecnych użytkowników).
- iOS: `ios/` (Swift, XcodeGen `project.yml`): WKWebView z `https://mealquo.mealquo.workers.dev` (app-bound domain), natywne przypomnienia (`Reminders.swift`, jak `remind.js`), Apple Health (`Health.swift`), udostępnianie, linki. CI `.github/workflows/ios.yml` kompiluje na symulator; podpis i App Store wymagają konta Apple Developer (`DEVELOPMENT_TEAM`).
- Polityka prywatności i usuwanie konta: `web/privacy.html`, `web/delete-account.html` (adresy `/privacy`, `/delete-account`); pola administratora do uzupełnienia.
- Zdjęcia posiłków: `/v2/photo/put|get`, pliki w `/var/lib/mealquo/photos/<user>`; w aplikacji `syncPhotos()` (photoId = `m<id posiłku>`).

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
