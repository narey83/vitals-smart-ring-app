#!/usr/bin/env bash
# Build this project directly on the host, with no dev container in the way.
#
# The container was Alpine (musl), which meant a gcompat shim to make Android's glibc build
# tools run at all, and a Docker NAT that hid the phone from adb. On the host both problems
# disappear: the build tools are native, and adb talks to the phone that is already paired
# with it.
#
# Ubuntu 24.04. Safe to re-run; every step checks before it acts.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
CMDLINE_VERSION="11076708_latest"   # cmdline-tools 16.0
PLATFORM="android-35"
BUILD_TOOLS="35.0.0"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

say "Java 17 and the tools the SDK needs"
if ! dpkg -s openjdk-17-jdk >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk unzip curl
else
    echo "already installed"
fi
JAVA_HOME_PATH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
echo "JAVA_HOME=$JAVA_HOME_PATH"

say "Android SDK at $SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/tools.zip" \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
else
    echo "command-line tools already present"
fi

export JAVA_HOME="$JAVA_HOME_PATH"
export ANDROID_SDK_ROOT="$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

say "Platform $PLATFORM, build-tools $BUILD_TOOLS, platform-tools"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS"

say "local.properties"
# Gitignored, so it never survives a move and has to be written locally each time.
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
printf 'sdk.dir=%s\n' "$SDK" > "$REPO/local.properties"
echo "wrote $REPO/local.properties"

say "Shell environment"
LINE_JAVA="export JAVA_HOME=$JAVA_HOME_PATH"
LINE_SDK="export ANDROID_SDK_ROOT=$SDK"
LINE_PATH='export PATH="$PATH:$ANDROID_SDK_ROOT/platform-tools"'
for line in "$LINE_JAVA" "$LINE_SDK" "$LINE_PATH"; do
    grep -qxF "$line" "$HOME/.bashrc" || echo "$line" >> "$HOME/.bashrc"
done
echo "added to ~/.bashrc (open a new shell, or source it, to pick them up)"

say "Checking it works"
cd "$REPO"
./gradlew :vitals:testDebugUnitTest

say "Phone"
"$SDK/platform-tools/adb" devices -l

cat <<EOF

Done. The unit tests above ran on the host, with no container involved.

If the phone is not listed, its wireless debugging pairing belongs to this machine already, so
it only needs reconnecting:

    adb connect <phone-ip>:<port>     # "IP address & Port" under Wireless debugging

Then, to see whether the ring really is sampling on its own:

    adb exec-out run-as uk.co.r99vitals cat files/readings.db > /tmp/readings.db
    sqlite3 -csv /tmp/readings.db 'select at,kind,value,extra,manual from readings order by at,id' > /tmp/readings.csv
    python3 tools/interval_check.py /tmp/readings.csv 15
EOF
