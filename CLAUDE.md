# Mealquo – aplikacja Android (liczenie kalorii i makro)

Osobny projekt, niezwiązany z ERP Interlook. Szczegóły w `README.md`.

## Struktura
- `android/` – projekt Androida. WebView z logiką w JavaScript plus natywne moduły: kamera, Gemini, Google Places, mowa, GPS, przypomnienia, Health Connect.
  - UI: `android/app/src/main/assets/www/app.js` i `styles.css` (tu zmienia się wygląd i zachowanie).
  - Natywne: `android/app/src/main/java/app/mealquo/` (`MainActivity.java` – mostek `MealquoNative`).
- `web/` – ten sam interfejs do podglądu w przeglądarce (`web.js`, `ios.css`); `app.js` i `styles.css` trzymaj w zgodzie z `android/.../assets/www/`.

## Budowanie
`./build.sh` buduje debug APK (Java 17, Gradle 8.5 z `~/gradle-8.5`, SDK w `~/AndroidSDK`) i zapisuje go w `~/backups/mealquo-RRRRMMDD_GGMM/mealquo-<wersja>.apk`. Gotowe APK przekaż użytkownikowi jako plik. Nie używaj dołączonego `gradlew`.

Przy nowej wersji podnieś `versionCode` i `versionName` w `android/app/build.gradle`.

## Klucze API
Klucze Gemini i Google Maps/Places są w `android/local.properties` (`GEMINI_KEY`, `MAPS_KEY`), poza gitem, i trafiają do kodu przez `BuildConfig`. Nigdy nie wpisuj ich w kod ani nie commituj. Według README warto je wymienić w konsoli Google, bo były w testowym APK.

## Stan
- Dane użytkownika w `localStorage` WebView (`talerz`).
- Subskrypcja 29 zł wyłączona (`locked()` w `app.js` zwraca `false`); kod płatności (Billing 6.2.1) jest w `MainActivity.java`.
