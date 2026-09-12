#!/usr/bin/env bash
# Installs selected SDK packages. Never replaces an AVD or auto-accepts licenses.
set -euo pipefail

with_emulators=false
if [ "$#" -gt 1 ]; then echo "Usage: $0 [--with-emulators]" >&2; exit 2; fi
case "${1:-}" in
  "") ;;
  --with-emulators) with_emulators=true ;;
  *) echo "Usage: $0 [--with-emulators]" >&2; exit 2 ;;
esac
repo_root=$(cd "$(dirname "$0")/.." && pwd)
case "$(uname -s)" in
  Darwin)
    default_sdk="$HOME/Library/Android/sdk"
    archive=commandlinetools-mac-13114758_latest.zip
    checksum=5673201e6f3869f418eeed3b5cb6c4be7401502bd0aae1b12a29d164d647a54e
    if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
      export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    fi
    ;;
  Linux)
    default_sdk="$HOME/Android/Sdk"
    archive=commandlinetools-linux-13114758_latest.zip
    checksum=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
    ;;
  *) echo "Supported hosts: macOS and Linux." >&2; exit 1 ;;
esac
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$default_sdk}}"
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ "$ANDROID_SDK_ROOT" != "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME and ANDROID_SDK_ROOT disagree. Set both to the same SDK directory." >&2
  exit 1
fi
if [ -n "${JAVA_HOME:-}" ]; then
  if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "JAVA_HOME must point to a JDK 17 with bin/java and bin/javac." >&2
    exit 1
  fi
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java_version=$(java -version 2>&1 | head -n 1 || true)
javac_version=$(javac -version 2>&1 || true)
if [[ ! "$java_version" =~ \"17[.\"] ]] || [[ ! "$javac_version" =~ ^javac\ 17[.\ ] ]]; then
  echo "Install JDK 17 and set JAVA_HOME before running setup." >&2
  exit 1
fi
for tool in curl unzip python3; do
  command -v "$tool" >/dev/null || { echo "Required command missing: $tool" >&2; exit 1; }
done

tools_dir="$ANDROID_HOME/cmdline-tools/19.0"
if [ -e "$tools_dir" ]; then
  if [ ! -x "$tools_dir/bin/sdkmanager" ] || ! grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*19\.0[[:space:]]*$' "$tools_dir/source.properties"; then
    echo "Existing $tools_dir is not command-line tools 19.0. Repair it manually; setup will not overwrite it." >&2
    exit 1
  fi
else
  temporary=$(mktemp -d)
  trap 'rm -rf "$temporary"' EXIT
  curl --fail --location --retry 3 "https://dl.google.com/android/repository/$archive" -o "$temporary/tools.zip"
  python3 - "$temporary/tools.zip" "$checksum" <<'PY'
import hashlib
import pathlib
import sys

actual = hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
if actual != sys.argv[2]:
    raise SystemExit(f"Command-line tools checksum mismatch: {actual}")
PY
  unzip -q "$temporary/tools.zip" -d "$temporary"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$temporary/cmdline-tools" "$tools_dir"
fi

packages=("platforms;android-36" "build-tools;35.0.0" "platform-tools")
if "$with_emulators"; then
  case "$(uname -m)" in
    arm64|aarch64) abi=arm64-v8a ;;
    x86_64) abi=x86_64 ;;
    *) echo "Unsupported emulator host architecture." >&2; exit 1 ;;
  esac
  image="system-images;android-36;google_apis;$abi"
  packages+=("emulator" "$image")
fi
echo "SDK packages are selected by package ID, not immutable revision."
echo "Review any license prompt yourself. Setup does not accept licenses for you."
"$tools_dir/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "${packages[@]}"
if "$with_emulators"; then
  avd_home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
  for name in bun-do-a bun-do-b; do
    if [ -e "$avd_home/$name.ini" ] || [ -e "$avd_home/$name.avd" ]; then
      echo "Keeping existing AVD $name unchanged. Inspect its configuration before testing."
    else
      # "no" only declines avdmanager's custom hardware profile prompt.
      printf 'no\n' | "$tools_dir/bin/avdmanager" create avd --name "$name" --package "$image" --device pixel_7
    fi
  done
  bash "$repo_root/tools/android-check-env.sh" --with-emulators
else
  bash "$repo_root/tools/android-check-env.sh"
fi
