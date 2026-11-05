#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: bash $0 <version> <build-number> <output-directory>" >&2
  exit 1
fi

app_version="$1"
build_number="$2"
if [[ ! "$app_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ || ! "$build_number" =~ ^[1-9][0-9]*$ ]]; then
  echo "Expected a semantic version and a positive build number" >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$3"
output_dir="$(cd "$3" && pwd)"
derived_data="$output_dir/DerivedData"
cd "$repo_root"

# Xcode also invokes Gradle for the iosArm64 Compose framework and resources.
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process"
xcodebuild \
  -project iosApp/SniffSubPlayer.xcodeproj \
  -scheme SniffSubPlayer \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$derived_data" \
  -onlyUsePackageVersionsFromResolvedFile \
  build \
  ARCHS=arm64 \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY= \
  MARKETING_VERSION="$app_version" \
  CURRENT_PROJECT_VERSION="$build_number" \
  2>&1 | tee "$output_dir/xcodebuild.log"

app_bundle="$derived_data/Build/Products/Release-iphoneos/SniffSubPlayer.app"
test -s "$app_bundle/SniffSubPlayer"
test -s "$app_bundle/compose-resources/composeResources/com.sniffsub.composeapp.resources/files/sniffer.js"
test ! -e "$app_bundle/embedded.mobileprovision"

# arm64 simulator binaries cannot be sideloaded even though their CPU is arm64.
test "$(xcrun lipo -archs "$app_bundle/SniffSubPlayer")" = arm64
xcrun vtool -show-build "$app_bundle/SniffSubPlayer" | grep -Eq 'platform[[:space:]]+IOS$'
python3 - "$app_bundle/Info.plist" "$app_version" "$build_number" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as source:
    info = plistlib.load(source)
expected = {
    "CFBundleIdentifier": "com.snifferhub.SniffSubPlayer",
    "CFBundleShortVersionString": sys.argv[2],
    "CFBundleVersion": sys.argv[3],
    "CFBundleSupportedPlatforms": ["iPhoneOS"],
}
for key, value in expected.items():
    if info.get(key) != value:
        raise SystemExit(f"Invalid {key}: {info.get(key)!r}, expected {value!r}")
PY

package_root="$(mktemp -d "$output_dir/package.XXXXXX")"
trap 'rm -rf "$package_root"' EXIT
mkdir -p "$package_root/Payload"
ditto "$app_bundle" "$package_root/Payload/SniffSubPlayer.app"

# No Apple identity or provisioning profile is included. A sideloading tool signs it.
staged_app="$package_root/Payload/SniffSubPlayer.app"
if codesign --display "$staged_app" >/dev/null 2>&1; then
  codesign --remove-signature "$staged_app"
fi
ipa="$output_dir/SniffSubPlayer-iOS-unsigned.ipa"
ditto -c -k --norsrc --keepParent "$package_root/Payload" "$ipa"
unzip -tq "$ipa"
test -s "$ipa"
printf 'Unsigned iOS IPA: %s (version %s, build %s)\n' "$ipa" "$app_version" "$build_number"
