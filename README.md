# Mealquo 14.4 — kod źródłowy

Aplikacja do liczenia kalorii i makro. Android to WebView z logiką w JavaScript. Natywna warstwa robi kamerę, Gemini, Places, mowę, lokalizację i przypomnienia.

Wersja w profilu: **14.4** (`versionCode` 144).

## Co jest w paczce

- `android/` — projekt Androida, to ta apka z telefonu.
- `web/` — ten sam interfejs plus `web.js` i `ios.css` do podglądu w przeglądarce. Na telefonie ich nie ma.

UI, które widać na ekranie, jest w `android/app/src/main/assets/www/app.js` i `styles.css`. To jest plik do poprawiania wyglądu i zachowania. Po zmianie trzeba zbudować APK od nowa.

## Gdzie co siedzi

| Plik | Rola |
|---|---|
| `assets/www/app.js` | Ekrany, dziennik, cele, przepisy, pytania, jednostki g/szt |
| `assets/www/styles.css` | Wygląd |
| `MainActivity.java` | WebView, mostek `MealquoNative`, logowanie, TTS, GPS |
| `Gemini.java` | Wywołania Gemini (zdjęcie, tekst, model Lite / Flash) |
| `Maps.java` | Restauracje i zdjęcia z Google Places |
| `PlateCameraActivity.java` | Kamera talerza |
| `FoodEye.java` | Szybkie rozpoznanie na kamerze |
| `LiveSpeech.java` | Mowa |
| `Journal.java` | Dziennik na Dysku |
| `Remind.java`, `RemindReceiver.java` | Przypomnienia |
| `Watch.kt` | Health Connect |

## Zbudować APK

Potrzebne: JDK 17, Android SDK (compileSdk 34), Gradle 8.2.

W `android/` nie ma `local.properties`. Utwórz go ze ścieżką do SDK:

```
sdk.dir=/Users/TY/Library/Android/sdk
```

Potem, w folderze `android` (jest już `gradlew`):

```
cd android
./gradlew :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

W Android Studio: Open → folder `android`.

## Zanim wrzucisz to na GitHuba

Klucze są wpisane na sztywno w `Gemini.java` i `Maps.java`. Przenieś je do `local.properties` / `BuildConfig` i nie commituj. Te klucze były w testowym APK, więc warto je wymienić w konsoli Google.

Subskrypcja 29 zł jest wyłączona: `locked()` w `app.js` zwraca `false`, ekran „Graj dalej” nie blokuje. Kod płatności (Billing 6.2.1) został w `MainActivity.java`.

## Stan, który developer powinien znać

- Konto i dziennik trzyma `localStorage` w WebView (`talerz`).
- Model AI przełącza się w profilu: szybki Lite albo dokładny Flash.
- Jednostka porcji to gramy albo sztuki. Pod liczbą jest linia w stylu `25 szt · 120 g`.
- Zdjęcia lokali idą z Places API. Nazwy dań z karty Google Maps przez Gemini.
- Gest wstecz na Androidzie woła `window.onAndroidBack`.
- Podgląd webowy (`web/`) nie ma kamery ani Places. Do testów na telefonie bierz APK.
