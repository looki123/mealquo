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

## Cele i gra (odchudzanie jako gra, trener AI)
- Wszystko w `app.js`, sekcja „Cele i gra” (od `const QUESTS`): misje dnia (`QUESTS`, `dayQuests`, `questState`), wyzwanie tygodnia (`WEEKLY`, `weekInfo`), XP i poziomy (`state.points`, `levelInfo`), odznaki (`badgeDefs`), ratunek serii (`state.game.freezes/frozen`, `rescueStreak`, uwzględniony w `streakCount`), ważenie i wykres (`state.weights`, `addWeight`, `weightChart`), postęp do celu (`state.goalStart`, `goalProgress`, `milestones`). `gameTick()` (start i ekran „Twoja droga”) przyznaje XP, odznaki, poziomy i woła trenera. Ekran: `journey()` (pasek `questBar` na starcie, karta w profilu). Style na końcu `styles.css`.
- Trener AI: Worker `/api/coach` (`cloudflare/src/coach.js` składa prompt z liczb od klienta; Claude Sonnet 5.5 przez `claude()`, zapas Gemini). Rodzaje: `goal` (ocena celu w `goalEdit`, kamienie milowe), `week` (podsumowanie tygodnia, fokus, wyzwanie i misje na tydzień; raz na tydzień), `day` (komentarz do dnia po 2–3 posiłkach, do 3 razy dziennie). Klient: `coachAsk`, `coachWeek`, `coachDay`, `coachGoal`; wynik w `state.coach`. Wyłącznik: ustawienie „Trener AI” (`settings.coach`). Testy promptu: `cloudflare/test/coach.test.mjs` (w `npm test`).
- Nowe pola stanu (`weights`, `quests`, `badges`, `game`, `coach`, `goalStart`) są w `SYNC_DOCS`, więc przechodzą na inny telefon konta. Tempo w `goalPlan` ograniczone do 1% masy ciała na tydzień.

## 15.6: wiek, przypomnienia o misjach, logowanie Google/Apple
- Wiek: `yearsOld(dob)`; poniżej 13 lat `setupDob` nie puszcza dalej; 13–15 lat = tryb nastolatka (`isTeen`, `teenGuard`, `teenCard`): plan bez deficytu (`goalPlan`/`calcPlan` wymuszają „keep”), misje z `TEEN_DAILY`/`TEEN_WEEKLY` (w tym `nodrink`), bez ważenia i celu; trener dostaje `profile.teen` (`coach.js` – „TRYB NASTOLATKA”). Zgoda rodzica: `/v2/consent/request|status|confirm` (tabela `parent_consents`, `server/schema.sql`), e-mail przez Brevo (`server/mail.mjs`, sekrety `BREVO_KEY`, `MAIL_FROM`, `PUBLIC_URL` w `/etc/mealquo-api.env`; bez klucza tylko zapis adresu), strona `web/consent.html` (`/consent?t=`).
- Przypomnienia o misjach: `missionNudges()` w `app.js` liczy pory i teksty (PL/UK/EN) i wysyła je w `syncReminders()` jako `missions`; platformy tylko planują: `Remind.java`/`RemindReceiver.java` (alarmy 31–36, limit `MISSION_MAX`=3 dziennie, 8–21), `cloudflare/src/remind.js` (`mission:*`, test `remind.test.mjs`), `ios/Mealquo/Reminders.swift` (`mission0–5`). Wyłącznik: ustawienie „Przypomnienia o misjach” (`settings.missions`).
- Poziomy: `levelFix()` raz dokłada XP do poziomu liczonego starą metodą (punkty/50), flaga `state.game.lvlFix` (synchronizowana).
- Logowanie Google/Apple: `server/auth.mjs` (JWKS, RS256, iss/aud/exp; test `server/test/auth.test.mjs`), `/v2/auth/config|google|apple` w `v2.mjs` (konto po e-mailu, `google_sub`/`apple_sub`; test `server/test/social.test.mjs` z podstawioną weryfikacją). Konfiguracja w `/etc/mealquo-api.env`: `GOOGLE_CLIENT_IDS` (web + Android, po przecinku), `APPLE_AUDIENCES` (`app.mealquo` + Services ID), `APPLE_SERVICES_ID` (web). Klient: `socialButtons`/`bindSocial`/`socialLogin` (przyciski tylko gdy serwer zgłasza konfigurację); Android `MealquoNative.googleSignIn()` (Credential Manager, `GOOGLE_WEB_CLIENT_ID` w `local.properties`), iOS `Auth.swift` (`appleSignIn`), web: Google Identity Services i Sign in with Apple JS.

## System projektowy (15.8)
- Cały wygląd w `web/styles.css`: tokeny w `:root` (kolory jasne, w `@media (prefers-color-scheme: dark)` ciemne; promienie `--r-*`, cienie `--shadow-*`, ruch `--ease`), typografia systemowa, odstępy 4 px. Klasy w `app.js` bez zmian nazw; nowe: `.home-top`, `.hero` (pierścień SVG `ring-*`, `macroBar`), `.sec` (nagłówek sekcji z akcją), `.missions/.mission`, `.empty` (puste stany), `.spinner/.skeleton`, `.act-list/.act-row` (arkusz dodawania), `.login-hero`.
- Ikony: `ICONS` + `icon(name)` w `app.js` (SVG liniowe); emoji zostały tylko w odznakach, sportach i flagach.
- Tryb ciemny: `color-scheme` w `index.html`, tokeny w CSS; Android – motyw `AppTheme` (`values-v29` DayNight, tło pod WebView wg `uiMode`), ciemny WebView działa od Androida 13.
- Zrzuty do porównań: `scratchpad/evalshot2.js` (DARK=1, FULL=1) z fixture `screens/*.js`.

## 15.9: dziennik, wygląd, kroki, aktualizacje
- „Dziennik” (`history()`): wykres 7 dni (SVG `.dchart`, słupki `dbar`, linia planu), dni rozwijane (`diaryOpen`) z posiłkami, treningami i krokami.
- Wygląd: `settings.theme` (light/dark/system, synchronizowane) → `applyTheme()` ustawia `data-theme` na `<html>` (CSS: tokeny ciemne pod `:root[data-theme="dark"]` i `prefers-color-scheme` bez `data-theme="light"`), meta `theme-color`, na Androidzie `MealquoNative.setTheme` (tło i ikony pasków).
- Imię: `state.user.displayName` (profil, opcjonalnie w `setupBody`); powitanie bez zgadywania z e-maila. Pasek „Dodaj do ekranu początkowego” tylko na iOS w Safari (`web.js`).
- Kroki: Android `Watch.kt` (`StepsRecord`, uprawnienie `READ_STEPS`, ta sama zgoda co zegarek), `MealquoNative.readSteps/askSteps` → `window.onSteps`; iOS `Health.swift` (`HKQuantityType(.stepCount)`); PWA brak. W `app.js`: `state.steps` (w `SYNC_DOCS`), `stepsKcal()` = (kroki − kroki z zapisanych spacerów/biegów) × 0,00035 × waga, `dayBurn()` dolicza do budżetu; misja `steps` (8000) gdy `stepsAvailable()`; wskazówka Samsung w profilu.
- Aktualizacje (tylko APK z GitHuba): `Updater.java` (`BuildConfig.UPDATES`, sprawdzenie co 6 h, pobranie do `cache/updates`, FileProvider, `REQUEST_INSTALL_PACKAGES`); wariant Play (`-Pplay`) ma `src/play/AndroidManifest.xml` usuwający uprawnienie i `UPDATES=false`. W `app.js`: `onUpdate`, `onUpdateProgress`, `updateCard()`.
- Wszystkie ikony to SVG (`ICONS`, `badgeArt`, `langTag`); emoji nie ma już w interfejsie.

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
- Aplikacja jest darmowa: od 15.8 nie ma subskrypcji ani Google Play Billing (kod i zależność usunięte); stary ekran `pay` w zapisanym stanie przekierowuje na start.

## Ograniczenie kluczy do aplikacji
Zapytania do Gemini i Places wysyłają `X-Android-Package` i `X-Android-Cert` (`AppKey.java`; w `app.js` przez `MealquoNative.keyHeaders()`), więc klucze można w konsoli Google ograniczyć do aplikacji Android `app.mealquo` + SHA-1 `59:BC:EB:0F:08:F2:27:62:55:6F:D7:C1:AF:29:9C:0E:9E:FD:8F:F4` (`~/.android/debug.keystore`). Nie zmieniaj ani nie zgub tego keystore: inny podpis oznacza, że klucze przestaną działać, a użytkownicy nie zaktualizują aplikacji.
