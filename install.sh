#!/usr/bin/env bash
set -euo pipefail

APK="$(./build.sh | tail -1)"
adb install -r "$APK"
adb shell monkey -p dev.dect.capturerouter 1
