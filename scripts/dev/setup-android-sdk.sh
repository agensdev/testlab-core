#!/usr/bin/env bash
# Dev-container setup: Android SDK, one x86_64 emulator image and the upstream handsets CLI
# (used only as the golden oracle for the dump renderers, and as the source of the pinned jar).
# Idempotent. Usage (as root in the dev container):  scripts/dev/setup-android-sdk.sh
set -euo pipefail
SDK=/opt/android-sdk
CMDLINE_ZIP=commandlinetools-linux-16111833_latest.zip
API=36
IMAGE="system-images;android-$API;google_apis;x86_64"
HS_VERSION=v0.1.38

export DEBIAN_FRONTEND=noninteractive
# Emulator runtime libraries (headless still loads them).
apt-get install -y -qq --no-install-recommends libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 \
  libxkbfile1 libxrandr2 libgbm1 libdrm2 libbsd0 libxkbcommon0 libxcb-cursor0 >/dev/null

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/c.zip" "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  unzip -q "$tmp/c.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export ANDROID_HOME=$SDK JAVA_HOME=${JAVA_HOME:-/opt/jdk-21}
sm="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$sm" --licenses >/dev/null 2>&1 || true
"$sm" --install "platform-tools" "emulator" "$IMAGE" "platforms;android-$API" "build-tools;36.0.0" >/tmp/sdkmanager.log 2>&1
cat > /etc/profile.d/android.sh <<EOF
export ANDROID_HOME=$SDK
export PATH=\$PATH:$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin
EOF

# One AVD: 2 GB RAM, 4 GB data, no snapshots (the host is short on RAM and disk).
if ! "$SDK/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: tl$API"; then
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "tl$API" -k "$IMAGE" -d pixel_6 >/dev/null
  cfg="$HOME/.android/avd/tl$API.avd/config.ini"
  sed -i -e 's/^hw.ramSize=.*/hw.ramSize=2048/' -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=4G/' "$cfg"
  grep -q '^hw.ramSize' "$cfg" || echo 'hw.ramSize=2048' >> "$cfg"
  grep -q '^hw.keyboard' "$cfg" && sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/' "$cfg" || echo 'hw.keyboard=yes' >> "$cfg"
fi

# Upstream handsets CLI, checksum-verified.
if [ ! -x /opt/handsets/hs ]; then
  tmp=$(mktemp -d)
  base="https://github.com/elliotgao2/handsets/releases/download/$HS_VERSION"
  curl -fsSL -o "$tmp/h.tgz" "$base/handsets-linux-x86_64.tar.gz"
  curl -fsSL -o "$tmp/h.sha" "$base/handsets-linux-x86_64.tar.gz.sha256"
  (cd "$tmp" && echo "$(awk '{print $1}' h.sha)  h.tgz" | sha256sum -c - >/dev/null)
  # The archive holds a single top-level `handsets/` folder.
  mkdir -p /opt/handsets && tar -xzf "$tmp/h.tgz" -C /opt/handsets --strip-components=1
  rm -rf "$tmp"
fi
echo "sdk: $(ls $SDK) | avd: $(ls $HOME/.android/avd | tr '\n' ' ') | handsets: $(ls /opt/handsets | tr '\n' ' ')"
