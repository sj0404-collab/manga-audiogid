#!/usr/bin/env bash
# Подготовка сборочного окружения в чистой Linux-песочнице (JDK 17 + Android SDK 34 + Gradle 8.9).
set -euo pipefail
TC=${TC:-/opt/tc}
mkdir -p "$TC"
sudo chown "$(whoami)" "$TC" 2>/dev/null || true

if [ ! -x "$TC/jdk17/bin/java" ]; then
  cd "$TC"
  curl -sSL -o jdk.tar.gz "https://cdn.azul.com/zulu/bin/zulu17.70.113-ca-jdk17.0.21.0.3-linux_x64.tar.gz"
  tar xzf jdk.tar.gz && mv zulu17* jdk17 && rm jdk.tar.gz
fi

export JAVA_HOME="$TC/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d "$TC/sdk/cmdline-tools/latest" ]; then
  cd "$TC"
  curl -sSL -o cl.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p sdk && unzip -q cl.zip -d sdk/tmp && mkdir -p sdk/cmdline-tools
  mv sdk/tmp/cmdline-tools sdk/cmdline-tools/latest && rm -rf sdk/tmp cl.zip
  export ANDROID_HOME="$TC/sdk"
  export ANDROID_SDK_ROOT="$TC/sdk"
  yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
  "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

if [ ! -d "$TC/gradle-8.9" ]; then
  cd "$TC"; curl -sSL -o g.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip"; unzip -q g.zip; rm g.zip
fi

echo "Готово. Запуск сборки:"
echo "  JAVA_HOME=$TC/jdk17 ANDROID_HOME=$TC/sdk GRADLE_USER_HOME=~/.cache/gradle $TC/gradle-8.9/bin/gradle :app:assembleRelease"
