#!/bin/sh
# Builds Cellular Companion for a GitHub release into build/.
#
# Releases are signed with a debug keystore (by default this machine's ~/.android/debug.keystore), whose certificate is the
# generic "Android Debug" one, so it doesn't identify whoever builds them. Use the same keystore for every release:
# Android only installs an update over an existing install if both are signed with the same key.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
keystore="${CELLULAR_COMPANION_KEYSTORE:-$HOME/.android/debug.keystore}"
sdk_dir="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")}"
build_tools=$(ls -d "$sdk_dir"/build-tools/* | sort -V | tail -n 1)

"$root/gradlew" -p "$root" --quiet assembleRelease

version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts")
unsigned="$root/app/build/outputs/apk/release/app-release-unsigned.apk"
aligned="$root/build/app-release-aligned.apk"
apk="$root/build/CellularCompanion-$version.apk"

mkdir -p "$root/build"
"$build_tools/zipalign" -P 16 -f 4 "$unsigned" "$aligned"
"$build_tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --out "$apk" "$aligned"
"$build_tools/apksigner" verify "$apk"
rm -f "$aligned" "$apk.idsig"
echo "Built $apk"
