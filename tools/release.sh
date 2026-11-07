#!/usr/bin/env bash
# Сборка подписанного release-APK с постоянным ключом проекта.
# Требуется JDK 17 и Android SDK 34; пути можно задать переменными JAVA_HOME / ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts | head -1)
CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts | head -1)
echo "Собираю версию $VERSION (versionCode $CODE)"

GRADLE=${GRADLE_BIN:-gradle}
"$GRADLE" --no-daemon :app:testDebugUnitTest :app:assembleRelease

OUT=artifacts
mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk   "$OUT/MangaBiblioGid-$VERSION-arm64-v8a.apk"
cp app/build/outputs/apk/release/app-armeabi-v7a-release.apk "$OUT/MangaBiblioGid-$VERSION-armeabi-v7a.apk"

APKSIGNER=${APKSIGNER:-apksigner}
for f in "$OUT"/MangaBiblioGid-$VERSION-*.apk; do
  echo "--- $f"
  "$APKSIGNER" verify --print-certs "$f" | grep -E "Signer #1 certificate (DN|SHA-256)"
done
echo "Готово. Подпись постоянная: обновления ставятся поверх, удалять приложение не нужно."
