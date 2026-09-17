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

# run the unit tests
# `test` rather than `testDebugUnitTest`: the latter is an Android-variant
# task and does not exist on the pure-JVM modules, so it skips most of them.
test *args:
    ./gradlew test {{ args }}

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

# show what the app is using on device
du:
    adb shell run-as {{ APPLICATION_ID }} du -sh files databases cache

# everything CI runs
ci: lint test build

clean:
    ./gradlew clean
