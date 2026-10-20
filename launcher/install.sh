#!/usr/bin/env bash
# Builds SBL and installs it on a TV over ADB.
#   TV_HOST=192.168.1.50 ./install.sh
# Needs JDK 17+, the Android SDK (ANDROID_HOME) and adb on PATH or in the SDK's platform-tools.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo ~/Android/Sdk)}"
ADB="$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

[ "${SKIP_BUILD:-}" = 1 ] || ./gradlew assembleRelease -q --console=plain

APK=app/build/outputs/apk/release/SlightlyBetterLauncher.apk
TARGET="$TV_HOST:5555"
"$ADB" connect "$TARGET"
"$ADB" -s "$TARGET" install -r "$APK"
# Watch Next rows and backups/themes on the TV's storage need these two; both can also be granted in SBL settings.
"$ADB" -s "$TARGET" shell pm grant com.bbu.sbl android.permission.READ_TV_LISTINGS
"$ADB" -s "$TARGET" shell appops set com.bbu.sbl MANAGE_EXTERNAL_STORAGE allow
"$ADB" -s "$TARGET" shell am start -n com.bbu.sbl/.MainActivity
echo "Installed. To make SBL the Home screen, press Home on the remote and choose it, or see README."
