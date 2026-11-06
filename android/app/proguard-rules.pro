# Metody wywoływane z app.js przez mostek MealquoNative
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# czytelne ślady stosu w raporcie błędów
-keepattributes SourceFile,LineNumberTable
