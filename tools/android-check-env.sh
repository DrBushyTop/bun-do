#!/usr/bin/env bash
# Read-only toolchain check. Revision differences warn; missing tools fail.
set -euo pipefail

with_emulators=false
if [ "$#" -gt 1 ]; then echo "Usage: $0 [--with-emulators]" >&2; exit 2; fi
case "${1:-}" in
  "") ;;
  --with-emulators) with_emulators=true ;;
  *) echo "Usage: $0 [--with-emulators]" >&2; exit 2 ;;
esac

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -n "${JAVA_HOME:-}" ]; then
  if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "JAVA_HOME must point to a JDK 17 with bin/java and bin/javac." >&2
    exit 1
  fi
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ "$(uname -s)" = Darwin ]; then default_sdk="$HOME/Library/Android/sdk"; else default_sdk="$HOME/Android/Sdk"; fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$default_sdk}}"
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ "$ANDROID_SDK_ROOT" != "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME and ANDROID_SDK_ROOT disagree. Set both to the same SDK directory." >&2
  exit 1
fi

failed=0
java_version=$(java -version 2>&1 | head -n 1 || true)
echo "Java: $java_version"
if [[ ! "$java_version" =~ \"17[.\"] ]]; then
  echo "ERROR: JDK 17 is required. Set JAVA_HOME to its installation directory." >&2
  failed=1
fi
javac_version=$(javac -version 2>&1 || true)
if [[ ! "$javac_version" =~ ^javac\ 17[.\ ] ]]; then
  echo "ERROR: javac 17 is required; found '$javac_version'. Set JAVA_HOME to a JDK 17." >&2
  failed=1
fi
echo "Android SDK: $ANDROID_HOME"

check_package() {
  local directory="$1" expected="$2" actual
  if [ ! -f "$ANDROID_HOME/$directory/source.properties" ]; then
    echo "ERROR: missing $directory. Run tools/android-setup.sh." >&2
    failed=1
    return
  fi
  actual=$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_HOME/$directory/source.properties" | tr -d '\r')
  echo "$directory: $actual"
  if [ "$actual" != "$expected" ]; then
    echo "WARNING: $directory revision $actual differs from tested revision $expected." >&2
  fi
}

check_executable() {
  if [ ! -x "$ANDROID_HOME/$1" ]; then
    echo "ERROR: missing executable $ANDROID_HOME/$1. Repair that SDK package." >&2
    failed=1
  fi
}

check_package cmdline-tools/19.0 19.0
check_package platforms/android-36 2
check_package build-tools/35.0.0 35.0.0
check_package platform-tools 37.0.1
check_executable cmdline-tools/19.0/bin/sdkmanager
check_executable platform-tools/adb
check_executable build-tools/35.0.0/aapt2
if "$with_emulators"; then
  case "$(uname -m)" in
    arm64|aarch64) abi=arm64-v8a ;;
    x86_64) abi=x86_64 ;;
    *) echo "ERROR: unsupported emulator host architecture." >&2; exit 1 ;;
  esac
  check_package emulator 37.1.11
  check_package "system-images/android-36/google_apis/$abi" 7
  check_executable emulator/emulator
  avd_home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
  for name in bun-do-a bun-do-b; do
    if [ ! -f "$avd_home/$name.ini" ]; then
      echo "ERROR: missing AVD $name. Run tools/android-setup.sh --with-emulators." >&2
      failed=1
    else
      echo "AVD $name: $avd_home/$name.ini"
    fi
  done
fi

if [ "$failed" -ne 0 ]; then exit 1; fi
echo "Required tools are present. This does not boot devices or verify a build."
