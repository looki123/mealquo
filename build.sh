#!/bin/bash
# Buduje debug APK Mealquo i zapisuje kopię w ~/backups/mealquo-RRRRMMDD_GGMM/
set -e
cd /home/ubuntu/mealquo/android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/AndroidSDK
test -f local.properties || { echo "Brak android/local.properties (sdk.dir, GEMINI_KEY, MAPS_KEY)"; exit 1; }

echo "Buduję APK..."
/home/ubuntu/gradle-8.5/bin/gradle :app:assembleDebug -q --no-daemon

VERSION=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
OUT="/home/ubuntu/backups/mealquo-$(date +%Y%m%d_%H%M)"
mkdir -p "$OUT"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/mealquo-$VERSION.apk"
echo "Gotowe: $OUT/mealquo-$VERSION.apk"

# ./build.sh --publish: commit musi być wypchnięty; tworzy tag v<wersja> i wydanie na GitHubie z APK
if [ "$1" = "--publish" ]; then
  cd /home/ubuntu/mealquo
  git diff --quiet && git diff --cached --quiet || { echo "Najpierw zacommituj zmiany"; exit 1; }
  git push -q origin main
  git tag -a "v$VERSION" -m "Mealquo $VERSION" && git push -q origin "v$VERSION"
  gh release create "v$VERSION" "$OUT/mealquo-$VERSION.apk#mealquo-$VERSION.apk" --title "Mealquo $VERSION" --generate-notes
  echo "Pobieranie: https://github.com/looki123/mealquo/releases/latest"
fi
