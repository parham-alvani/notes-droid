# Homebrew's JDKs are not registered with /usr/libexec/java_home, and the cask
# installs the SDK outside the default location, so both are pointed at
# explicitly. An existing environment value always wins.
export JAVA_HOME := env_var_or_default("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
export ANDROID_HOME := env_var_or_default("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")

APPLICATION_ID := "me.parham1995.notes"

default:
    @just --list

# one-time: install the Android SDK and accept its licences
[group('setup')]
sdk:
    brew install --cask android-commandlinetools
    sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
    yes | sdkmanager --licenses

# check that the toolchain is actually usable
[group('setup')]
doctor:
    @echo "JAVA_HOME    = $JAVA_HOME"
    @echo "ANDROID_HOME = $ANDROID_HOME"
    @"$JAVA_HOME/bin/java" -version
    @test -d "$ANDROID_HOME/platforms/android-37.0" && echo "platform      ok" || echo "platform      MISSING - run 'just sdk'"
    @test -d "$ANDROID_HOME/build-tools/37.0.0" && echo "build-tools   ok" || echo "build-tools   MISSING - run 'just sdk'"
    @echo "sdk.dir       $(grep -s '^sdk.dir' local.properties || echo 'MISSING in local.properties')"

# assemble the debug apk
build *args:
    ./gradlew assembleDebug {{ args }}

# assemble the minified release apk
release *args:
    ./gradlew assembleRelease {{ args }}

# `test` rather than `testDebugUnitTest`: the latter is an Android-variant
# task and does not exist on the pure-JVM modules, so it skips most of them.
# `verifyRoborazziDebug` is what puts the app's test run in verify mode: a
# pixel that moved fails it, and the diff lands in app/build/outputs/roborazzi.
# run the unit tests and compare the renderer's screenshots with their goldens
test *args:
    ./gradlew test verifyRoborazziDebug {{ args }}

# redraw the screenshot goldens in app/src/test/screenshots after a deliberate change
screenshots-record:
    ./gradlew recordRoborazziDebug --tests '*ScreenshotTest*'
    @git status --short app/src/test/screenshots

[group('lint')]
[private]
ktlint:
    ./gradlew ktlintCheck

[group('lint')]
[private]
android-lint:
    ./gradlew lintDebug

# run ktlint and android lint
lint: ktlint android-lint

# auto-format the kotlin sources
fmt:
    ./gradlew ktlintFormat

# install the debug apk on the connected device
install: build
    ./gradlew installDebug

# install and launch on the connected device
run: install
    adb shell am start -n {{ APPLICATION_ID }}/.MainActivity

# tail the app's logs
logs:
    adb logcat --pid=$(adb shell pidof -s {{ APPLICATION_ID }})

# wipe app data on the device, forcing a fresh sync
wipe:
    adb shell pm clear {{ APPLICATION_ID }}

# pull the on-device database for inspection
pull-db:
    adb exec-out run-as {{ APPLICATION_ID }} cat databases/notes.db > /tmp/notes.db
    @echo "wrote /tmp/notes.db"

# rebuild the bundled English dictionary from Open English WordNet
# The release is pinned by checksum, and the output is committed: an ordinary
# build never downloads it.
dictionary:
    #!/usr/bin/env bash
    set -euo pipefail
    edition=2025
    sha=73355e48f8117a24ca9ebc23ed75b35434e6cd21cc9dd3984e80aff5a5f63636
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    curl -fsSL -o "$work/wordnet.zip" \
      "https://github.com/globalwordnet/english-wordnet/releases/download/$edition-edition/english-wordnet-$edition.zip"
    echo "$sha  $work/wordnet.zip" | shasum -a 256 -c -
    unzip -q "$work/wordnet.zip" -d "$work"
    ./gradlew -q :core:dictionary:importWordNet -Pwordnet="$work/oewn$edition"
    ls -l app/src/main/assets/dictionary

# show what the app is using on device
du:
    adb shell run-as {{ APPLICATION_ID }} du -sh files databases cache

# everything CI runs
ci: lint test build

clean:
    ./gradlew clean

# record the baseline profile from the connected device -- UNINSTALLS THE APP THERE, vault and SSH key included
[group('release')]
baseline-profile:
    #!/usr/bin/env bash
    set -euo pipefail
    # The connected-test task that drives the recording uninstalls the app
    # under test around the run. On the one phone this app lives on, that is
    # the synced vault, the token, the on-device SSH key and every setting --
    # which is exactly what happened the first time this ran. Only an
    # emulator, or a device whose vault you are willing to set up again.
    if [ "${DAFTAR_WIPE_OK:-}" != "1" ]; then
        echo "baseline-profile uninstalls the app on the connected device, data and all." >&2
        echo "Run it against an emulator, or set DAFTAR_WIPE_OK=1 if losing the vault there is fine." >&2
        exit 1
    fi
    ./gradlew :app:generateBaselineProfile
    echo "written: app/src/main/generated/baselineProfiles/ -- commit both files"

# check a release apk still carries what R8 cannot see
[group('release')]
verify-apk apk="app/build/outputs/apk/release/app-release.apk":
    ./tools/verify-apk.sh {{ apk }}

# set the release version everywhere (e.g. `just bump 0.2.0`)
[group('release')]
bump version:
    #!/usr/bin/env bash
    set -euo pipefail
    # A monotonic integer derived from the version, so it can never go
    # backwards and never needs remembering. 0.2.0 -> 200, 1.12.3 -> 11203.
    code=$(echo "{{ version }}" | awk -F. '{ printf "%d", $1 * 10000 + $2 * 100 + $3 }')
    perl -pi -e "s/versionCode = \d+/versionCode = $code/" app/build.gradle.kts
    perl -pi -e 's/versionName = "[^"]*"/versionName = "{{ version }}"/' app/build.gradle.kts
    log="fastlane/metadata/android/en-US/changelogs/$code.txt"
    [ -f "$log" ] || printf 'Describe what changed in {{ version }}.\n' > "$log"
    echo "version {{ version }}, code $code"
    echo
    echo "next:"
    echo "  1. write $log"
    echo "  2. git commit -am 'chore: release {{ version }}'"
    echo "  3. git tag -a v{{ version }} -m 'v{{ version }}' && git push --follow-tags"

# what the release workflow checks before it publishes anything
[group('release')]
release-check:
    @declared=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts); \
     code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts); \
     echo "versionName $declared / versionCode $code"; \
     log="fastlane/metadata/android/en-US/changelogs/$code.txt"; \
     if ! test -f "$log"; then echo "changelog      MISSING - run 'just bump $declared'"; \
     elif ! git ls-files --error-unmatch "$log" >/dev/null 2>&1; then \
       echo "changelog      UNTRACKED - 'git add $log' first; 'commit -a' does not add new files, and the APK's What's new is built from the tree"; \
     elif grep -q '^Describe what changed' "$log"; then echo "changelog      PLACEHOLDER - write $log"; \
     else echo "changelog      ok"; fi
