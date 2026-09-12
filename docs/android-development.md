# Android development

The native app lives in `src/BunDo.Android`. The first slice is an anonymous,
offline inbox. It does not sign in, contact the backend, or sync between devices.
Use [local development](local-development.md) when working on the separate Aspire
backend. Do not treat two working inboxes as proof of sync.

For opt-in native/Parakeet/Azure speech tests, private WAV manifests and latency
measurements, use [speech comparison](android-speech-comparison.md). Install test
APKs directly with `adb install -r` when preserving an installed speech model.

## Toolchain

The Gradle wrapper pins Gradle 8.13 and verifies its download with SHA-256.
The root build pins Android Gradle Plugin 8.13.2, Kotlin 2.2.21 and KSP 2.3.4.
The app uses JDK 17, compile/target API 36 and minimum API 26.

The local baseline verified on September 12, 2026 is:

| Component | Version |
| --- | --- |
| Homebrew OpenJDK | 17.0.20.1 |
| Android command-line tools | 19.0, archive build 13114758 |
| Android API 36 platform | Revision 2 |
| Build tools | 35.0.0 |
| Platform tools | 37.0.1 |
| Emulator | 37.1.11 |
| Google APIs API 36 ARM64 image | Revision 7 |
| AVD profiles | `bun-do-a`, `bun-do-b`, Pixel 7 |

CI pins Temurin `17.0.20.1+1` on Ubuntu 24.04. SDK setup pins the command-line
tools archive by build number and SHA-256. Google's SDK package IDs do not pin
their revisions. Installing `platform-tools` or an API image can fetch a newer
revision later. The environment check prints installed versions and warns when
they differ from the local baseline. It does not claim a byte-for-byte SDK lock.

The command-line tools SHA-256 values are:

```text
macOS  5673201e6f3869f418eeed3b5cb6c4be7401502bd0aae1b12a29d164d647a54e
Linux  7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
```

These match the build 13114758 downloads from Google's Android SDK repository.
Its published SHA-1 checksums were also checked before recording these hashes.

## Install and check

Install a JDK 17 first. On Apple Silicon with Homebrew:

```sh
brew install openjdk@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

On Linux, set `JAVA_HOME` to your JDK 17 and `ANDROID_HOME` to your SDK directory,
usually `$HOME/Android/Sdk`. If you also set `ANDROID_SDK_ROOT`, it must match
`ANDROID_HOME`. The scripts use the platform default when neither variable is set.
They select the Homebrew JDK above when `JAVA_HOME` is absent on Apple Silicon.
They do not alter your shell profile or install Java.

From the repository root:

```sh
bash tools/android-setup.sh
bash tools/android-check-env.sh
```

Setup needs `curl`, `unzip` and Python 3. Review Android's license prompts yourself.
The script does not pipe acceptance into `sdkmanager`, overwrite existing
command-line tools, replace AVDs, start emulators or wipe app data.

To install the emulator image for your host architecture and create both profiles:

```sh
bash tools/android-setup.sh --with-emulators
bash tools/android-check-env.sh --with-emulators
```

Existing profiles remain unchanged. Inspect their configuration with
`"$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager" list avd` before using them.
The check confirms their registration files exist; it does not assert their
hardware configuration or that they boot. Linux emulators need working KVM
acceleration. Apple Silicon uses the ARM64 image.

## Build and tests

```sh
cd src/BunDo.Android
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

Reports are in `app/build/reports/`. APKs are in `app/build/outputs/apk/`.
The Android CI job runs this command on Ubuntu without an emulator. It compiles
instrumentation tests but does not execute them.

Also run the repository checks from the root:

```sh
python3 tools/check_invariants.py
python3 -m unittest discover -s tools -p 'test_*.py'
```

## Two-profile device verification

Use the T3 device panel when it is available. Otherwise start these in separate
terminals. Ports must be free; do not stop unrelated devices to acquire them.

```sh
"$ANDROID_HOME/emulator/emulator" -avd bun-do-a -port 5554 -no-snapshot
"$ANDROID_HOME/emulator/emulator" -avd bun-do-b -port 5556 -no-snapshot
```

`-no-snapshot` disables snapshot load/save; it does not erase installed apps or
their database. Check each device explicitly:

```sh
adb devices -l
adb -s emulator-5554 shell getprop sys.boot_completed
adb -s emulator-5556 shell getprop sys.boot_completed
```

Both boot properties must return `1` before running tests. With both profiles
booted, run instrumentation against each serial:

```sh
cd src/BunDo.Android
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest
```

Save the first run's reports before the second if separate evidence is needed.
Instrumentation changes test databases and test UI state. Use development
profiles, not a device with work you need to keep.

Install the app for manual checks, selecting every device explicitly:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n fi.bundo/.MainActivity
adb -s emulator-5556 shell am start -n fi.bundo/.MainActivity
```

On each profile:

1. Turn off Wi-Fi and mobile data in Android settings.
2. Capture several tasks. Edit a title and description, then check that task
   detail still exposes the original captured text. Completion is a later slice.
3. Force-stop Bun Do through Android settings, reopen it and check saved work.
4. Rotate the device with unsaved capture or rename text and check recovery.
5. Check English and Finnish app language. Use a large font setting and check
   that controls remain reachable.
6. Verify that tasks entered on one profile do not appear on the other.

Record serials, image revisions, test counts and the actions actually checked in
the slice evidence. Do not substitute a compiled test APK for a passing device
test. Later sign-in and sync slices must add their own cross-profile checks.

## Shell evidence

The September 12, 2026 shell run passed the debug build, lint, three JVM tests
and eleven instrumentation tests on each of `bun-do-a` and `bun-do-b`. Both are
Pixel 7 API 36 ARM64 emulators, not the target physical phones. The
`tools/android-restart-smoke.py` script also verified committed task and unfinished
draft durability through a real force-stop on each profile with connectivity off.

TalkBack traversal, physical-device performance and the full release flows remain
unverified.
