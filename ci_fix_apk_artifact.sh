set -euo pipefail

# 1) убедимся что мы на stable_build
cur="$(git branch --show-current)"
if [ "$cur" != "stable_build" ]; then
  echo "ERROR: switch to stable_build first. Current: $cur"
  exit 1
fi

WF=".github/workflows/android.yml"
if [ ! -f "$WF" ]; then
  echo "ERROR: $WF not found"
  exit 1
fi

# 2) перезапишем workflow аккуратно: build + upload artifact
cat > "$WF" <<'YML'
name: Android CI

on:
  push:
    branches: [ "main", "stable_build" ]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Build Debug APK
        run: ./gradlew :app:assembleDebug

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error

      - name: Install sqlite3
        run: sudo apt-get update && sudo apt-get install -y sqlite3

      - name: CI SQL checks on test database
        run: |
          set -e
          test -f testdata/data.sqlite
          ls -la testdata/data.sqlite
          echo "Tables:"
          sqlite3 testdata/data.sqlite ".tables"
YML

# 3) commit + push (триггерит action)
git add "$WF"
git commit -m "ci: build stable_build + upload debug apk artifact" || true
git push

echo "DONE: pushed."
echo "Open GitHub -> Actions -> latest run -> Artifacts -> app-debug-apk"
