#!/usr/bin/env bash
# Rebuild the launcher with this TV's settings and install it as Home.
# Run from the repo root:   TV_HOST=192.168.1.50 work/build-and-install.sh
#
# Settings live in launcher/app/src/main/res/values/config.xml (dock_apps, columns, hidden_apps,
# live_tv_package). launcher/build-and-install.sh does the work and its safety checks: it starts the
# new launcher before the stock one is touched, and puts the stock one back if Home ends up elsewhere.
#
# On this Mac Java cannot reach GitHub's download host, so the Gradle 8.9 zip was fetched with curl
# into ~/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/ (SHA-256 matches the
# wrapper's distributionSha256Sum). Once there, it is reused.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
# INPUT_KEY_SERVICE stays 0 on this TV. With the helper bound, a held volume button moves one step per
# press (docs/devices/tcl-55p71b-g09-key-repeat.md). Netflix (190) and Prime Video (191) do not need it:
# they are global keys handled by com.xiaomi.android.tvsetup.partnercustomizer. Only the Mi button (189,
# app_keys 189:home in config.xml) needs the helper; INPUT_KEY_SERVICE=1 turns it on at that price.
export INPUT_KEY_SERVICE="${INPUT_KEY_SERVICE:-0}"
launcher/build-and-install.sh
tools/venv/bin/python tools/tv.py 'input keyevent HOME; sleep 2; dumpsys activity activities | grep mResumedActivity'
