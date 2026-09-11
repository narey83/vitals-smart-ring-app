#!/usr/bin/env bash
# Make this PC the home Gitea's CI runner for this repository.
#
# .github/workflows/build.yml runs on GitHub's machines and on this one alike. It asks for
# ubuntu-latest; this runner answers to that name but builds on the PC itself rather than in a
# container. There is no Docker here, and the JDK and Android SDK that tools/setup-host.sh
# installed are already on disk. It also signs with this PC's own debug key, the one the phone's
# app was installed with, so Gitea needs no signing secret.
#
# Host mode means a workflow runs as you, on this machine. That is fine for your own repository
# on your own Gitea. Do not register it anywhere other people can push workflows.
#
# Usage:  tools/setup-runner.sh <registration token>
#   The token is in Gitea, under the repository's Settings → Actions → Runners → Create new
#   runner. GITEA_URL points it at another instance (default https://gitea.homenetwork.lan).
#
# Safe to re-run: it replaces the binary and restarts the service, and registers only once, so
# the token is only needed the first time.
set -euo pipefail

GITEA_URL="${GITEA_URL:-https://gitea.homenetwork.lan}"
VERSION="3.4.2"
# Published alongside the release, for the .xz this downloads.
SHA256="5ff5a540b65128f4a72df9f69d2a425c6e93f2fcbcde8915a2acf76dd6732f11"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
DIR="$HOME/.local/share/gitea-runner"
BIN="$DIR/gitea-runner"
TOKEN="${1:-}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

if [ ! -f "$DIR/.runner" ] && [ -z "$TOKEN" ]; then
    echo "usage: $0 <registration token>" >&2
    echo "Find it in Gitea: the repository's Settings → Actions → Runners → Create new runner." >&2
    exit 1
fi
[ -d "$SDK" ] || { echo "No Android SDK at $SDK — run tools/setup-host.sh first." >&2; exit 1; }
command -v node >/dev/null || { echo "Node.js is needed to run actions on the host: sudo apt-get install nodejs" >&2; exit 1; }

say "gitea-runner $VERSION"
mkdir -p "$DIR"
download="$(mktemp -d)"
trap 'rm -rf "$download"' EXIT
curl -fsSL -o "$download/runner.xz" \
    "https://gitea.com/gitea/runner/releases/download/v$VERSION/gitea-runner-$VERSION-linux-amd64.xz"
echo "$SHA256  $download/runner.xz" | sha256sum -c -
xz -dc "$download/runner.xz" > "$BIN.new"
chmod +x "$BIN.new"
mv "$BIN.new" "$BIN"

say "Config"
cat > "$DIR/config.yaml" <<EOF
log:
  level: info
runner:
  file: $DIR/.runner
  capacity: 1
  envs:
    ANDROID_HOME: $SDK
    ANDROID_SDK_ROOT: $SDK
  labels:
    - "ubuntu-latest:host"
EOF

say "Registering with $GITEA_URL"
if [ -f "$DIR/.runner" ]; then
    echo "already registered"
else
    "$BIN" register --config "$DIR/config.yaml" --no-interactive \
        --instance "$GITEA_URL" --token "$TOKEN" \
        --name "$(hostname)-android" --labels "ubuntu-latest:host"
fi

say "Service"
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/gitea-runner.service" <<EOF
[Unit]
Description=Gitea Actions runner for vitals-smart-ring-app
After=network-online.target

[Service]
WorkingDirectory=$DIR
ExecStart=$BIN daemon --config $DIR/config.yaml
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable gitea-runner.service
systemctl --user restart gitea-runner.service
# A user service stops at logout unless the user lingers.
loginctl enable-linger "$USER" 2>/dev/null ||
    echo "Could not keep it running after logout; sudo loginctl enable-linger $USER does."

say "Done"
echo "It should show as idle in Gitea under Settings → Actions → Runners."
echo "Logs: journalctl --user -u gitea-runner -f"
