#!/usr/bin/env bash
# Undo everything this session changed on the Mi TV 4A Pro 43 (MiTV-AXSO0, Android TV 9, PI.6036).
# Run from the repo root:   TV_HOST=192.168.1.50 work/restore-all.sh
#
# 1. Re-enables the 11 packages disabled in four batches, using the records tools/disable.sh wrote
#    in ~/.cleanyourtv/ (one per batch list), and puts the stock Google TV launcher back as Home.
# 2. Switches Private DNS back to the default (opportunistic = automatic).
# The new launcher (com.gathin.tvhome) stays installed but is no longer Home; remove it with
#    tools/tv.py 'pm uninstall com.gathin.tvhome'   (it is your own app, not a system package).
# com.android.tv (the Live TV app) was restored this session because an earlier ADB AppControl
# session had removed it and Settings > Inputs could not open HDMI without it. It is left in place.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
PY=tools/venv/bin/python

for b in work/batch4.txt work/batch3.txt work/batch2.txt; do
  tools/restore.sh "$b"
done
# Last batch carries the stock launcher, so Home goes back to it.
tools/restore.sh work/batch1.txt com.google.android.tvlauncher

"$PY" tools/tv.py 'settings put global private_dns_mode opportunistic'
"$PY" tools/tv.py 'settings delete global private_dns_specifier'

echo "Disabled packages now:"
"$PY" tools/tv.py 'pm list packages -d'
echo "Home now:"
"$PY" tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1'
