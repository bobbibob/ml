set -euo pipefail

# 0) перейти на stable_build
git checkout stable_build

# 1) откатить ветку на рабочий коммит
git reset --hard cce4c14

# 2) гарантировать, что workflow билдит stable_build и прикладывает APK как artifact
WF=".github/workflows/android.yml"
mkdir -p .github/workflows

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
          echo "svodka rows:"
          sqlite3 testdata/data.sqlite "SELECT COUNT(*) FROM svodka;"
YML

git add "$WF"
git commit -m "ci: build stable_build + upload debug apk artifact" || true

# 3) force-push stable_build чтобы GitHub Actions собрал именно это состояние
git push --force-with-lease origin stable_build

echo "DONE: pushed stable_build at cce4c14 + artifact workflow."
echo "Go to GitHub -> Actions -> latest run -> Artifacts -> app-debug-apk"
