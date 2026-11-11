#!/usr/bin/env bash
# build.sh OUT_DIR — builds TestShop.app for the iOS Simulator with swiftc (no Xcode project), and
# signs it ad hoc, which is all a simulator needs. Prints the .app path.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/build}
app="$out/TestShop.app"
rm -rf "$app"; mkdir -p "$app"
arch=$(uname -m); [ "$arch" = arm64 ] || arch=x86_64
sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun --sdk iphonesimulator swiftc -parse-as-library -O \
  -target "$arch-apple-ios17.0-simulator" -sdk "$sdk" \
  "$here/TestShop.swift" -o "$app/TestShop"
cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>com.example.testshop</string>
  <key>CFBundleExecutable</key><string>TestShop</string>
  <key>CFBundleName</key><string>TestShop</string>
  <key>CFBundleDisplayName</key><string>Test Shop</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.2.0</string>
  <key>CFBundleVersion</key><string>42</string>
  <key>MinimumOSVersion</key><string>17.0</string>
  <key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>UILaunchScreen</key><dict/>
  <key>UIApplicationSceneManifest</key><dict><key>UIApplicationSupportsMultipleScenes</key><false/></dict>
  <key>CFBundleURLTypes</key><array><dict><key>CFBundleURLSchemes</key><array><string>testshop</string></array></dict></array>
</dict></plist>
PLIST
codesign --force --sign - "$app" >/dev/null
echo "$app"
