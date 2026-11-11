#!/usr/bin/env bash
# build-fixture.sh — the install-test fixture APKs: resource-only (no code), a few KB, installable
# on any Android 7+ device or emulator. Built with the SDK's aapt2, zipalign and apksigner:
#   fixture-v1.apk         no.agens.testlab.fixture 1 "1.0-fixture", key A
#   fixture-v2.apk         same package, versionCode 2, key A        (an update)
#   fixture-otherkey.apk   same package, versionCode 3, key B        (signature mismatch)
# The keys are throwaway and not kept; rebuilding gives new signatures, which the tests don't mind.
#   tools/testapp/build-fixture.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

keytool -genkeypair -keystore "$work/a.jks" -storepass android -keypass android -alias a -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Testlab fixture A" >/dev/null 2>&1
keytool -genkeypair -keystore "$work/b.jks" -storepass android -keypass android -alias b -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Testlab fixture B" >/dev/null 2>&1

build() { # name versionCode versionName keystore
  sed -e "s/@CODE@/$2/" -e "s/@NAME@/$3/" AndroidManifest.xml.in > "$work/AndroidManifest.xml"
  "$BT/aapt2" link -o "$work/u.apk" -I "$JAR" --manifest "$work/AndroidManifest.xml"
  "$BT/zipalign" -f 4 "$work/u.apk" "$work/z.apk"
  "$BT/apksigner" sign --ks "$work/$4.jks" --ks-pass pass:android --out "$1" "$work/z.apk"
  rm -f "$1.idsig"
  echo "$1: $(stat -c %s "$1") bytes, $("$BT/aapt2" dump badging "$1" | head -1)"
}
build fixture-v1.apk 1 1.0-fixture a
build fixture-v2.apk 2 2.0-fixture a
build fixture-otherkey.apk 3 3.0-otherkey b
