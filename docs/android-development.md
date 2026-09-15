# Android development

The Android app is in `src/BunDo.Android`. Use this guide for local setup and
manual two-emulator checks. Gradle files and the CI workflow own dependency,
SDK, and test configuration. The application source owns its current features.

Use [development sign-in](identity-development.md) for Microsoft and Local
identity checks. Use [speech comparison](android-speech-comparison.md) only for
the opt-in recognizer experiment. Shared household tasks synchronize; the private local inbox does not. Two working private inboxes are not sync evidence.

## Install and check

Install JDK 17 and an Android SDK. On Apple Silicon with Homebrew:

```sh
brew install openjdk@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

On Linux, set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK directory. If
`ANDROID_SDK_ROOT` is also set, it must match `ANDROID_HOME`.

From the repository root:

```sh
bash tools/android-setup.sh
bash tools/android-check-env.sh
```

The setup script needs `curl`, `unzip`, and Python 3. Review Android license
prompts yourself. It does not alter shell profiles, overwrite existing command
line tools, replace AVDs, start emulators, or wipe app data.

To create the two dedicated emulator profiles:

```sh
bash tools/android-setup.sh --with-emulators
bash tools/android-check-env.sh --with-emulators
```

Existing profiles remain unchanged. Linux emulators need working KVM
acceleration. Apple Silicon uses the ARM64 image.

## Build and automated checks

```sh
cd src/BunDo.Android
./gradlew --no-daemon assembleDebug assembleLocal testDebugUnitTest testLocalUnitTest lintDebug lintLocal assembleDebugAndroidTest

cd ../..
python3 tools/check_invariants.py
python3 -m unittest discover -s tools -p 'test_*.py'
```

The Android build compiles instrumentation tests but does not run them. Reports
and APK locations are standard Gradle output paths.

## Two-profile device verification

Use the T3 device panel when available. Otherwise start these in separate
terminals. Ports must be free. Do not stop unrelated devices to acquire them.

```sh
"$ANDROID_HOME/emulator/emulator" -avd bun-do-a -port 5554 -no-snapshot
"$ANDROID_HOME/emulator/emulator" -avd bun-do-b -port 5556 -no-snapshot

adb devices -l
adb -s emulator-5554 shell getprop sys.boot_completed
adb -s emulator-5556 shell getprop sys.boot_completed
```

Both boot properties must return `1`. `-no-snapshot` disables snapshot
load/save. It does not erase installed apps or their data.

Run instrumentation against each device:

```sh
cd src/BunDo.Android
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest
```

Instrumentation changes test databases and UI state. Use development profiles,
not a device with work you need to keep.

Install the app for manual checks, selecting every device explicitly:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n fi.bundo/.MainActivity
adb -s emulator-5556 shell am start -n fi.bundo/.MainActivity
```

On each profile:

1. Turn off Wi-Fi and mobile data.
2. Capture tasks, edit text, and confirm that the original capture remains
   available in task detail.
3. Force-stop and reopen the app to check local durability.
4. Rotate with unfinished editor text and check draft recovery.
5. Check Finnish and English plus a large font setting.
6. In a shared household, reconnect each profile in both orders and confirm accepted work converges without losing offline edits. Check private inbox and account isolation separately.

Record the serials, image revisions, checks actually run, and results in the
owning slice issue. A compiled APK is not device-test evidence. Physical-phone
performance, TalkBack traversal, and full release flows remain separate work.
