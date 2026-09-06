#!/usr/bin/env bash
set -euo pipefail

cd /work
openbox > openbox.log 2>&1 &
wm_ready=false
for attempt in {1..100}; do
  if xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id #'; then
    wm_ready=true
    break
  fi
  sleep 0.1
done
if [[ "$wm_ready" != true ]]; then
  echo 'The isolated window manager did not become ready.' >&2
  exit 1
fi

uname -srm
java -version
xdpyinfo | sed -n '/name of display:/p; /dimensions:/p'
xprop -root _NET_SUPPORTING_WM_CHECK
./gradlew "$@"
