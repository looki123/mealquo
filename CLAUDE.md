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

## GitHub (publiczne repo `looki123/mealquo`)
- Kod: https://github.com/looki123/mealquo. Commituj i wypychaj (`git push`).
- Wydanie z APK: podnieś wersję, zacommituj, potem `./build.sh --publish`. Tworzy tag `v<wersja>` i release z `mealquo-<wersja>.apk`.
- Najnowsze APK dla użytkowników: https://github.com/looki123/mealquo/releases/latest
- Repo jest publiczne: nigdy nie commituj `local.properties`, kluczy ani APK.

## Klucze API
Klucze Gemini i Google Maps/Places są w `android/local.properties` (`GEMINI_KEY`, `MAPS_KEY`), poza gitem, i trafiają do kodu przez `BuildConfig`. Nigdy nie wpisuj ich w kod ani nie commituj. Według README warto je wymienić w konsoli Google, bo były w testowym APK.

## Stan
- Dane użytkownika w `localStorage` WebView (`talerz`).
- Subskrypcja 29 zł wyłączona (`locked()` w `app.js` zwraca `false`); kod płatności (Billing 6.2.1) jest w `MainActivity.java`.

## Ograniczenie kluczy do aplikacji
Zapytania do Gemini i Places wysyłają `X-Android-Package` i `X-Android-Cert` (`AppKey.java`; w `app.js` przez `MealquoNative.keyHeaders()`), więc klucze można w konsoli Google ograniczyć do aplikacji Android `app.mealquo` + SHA-1 `59:BC:EB:0F:08:F2:27:62:55:6F:D7:C1:AF:29:9C:0E:9E:FD:8F:F4` (`~/.android/debug.keystore`). Nie zmieniaj ani nie zgub tego keystore: inny podpis oznacza, że klucze przestaną działać, a użytkownicy nie zaktualizują aplikacji.
