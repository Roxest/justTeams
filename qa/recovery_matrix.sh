#!/usr/bin/env bash
# Drives qa/chest_recovery.py across all cases with a cold reboot between fault and recovery.
set -euo pipefail
cd "$(dirname "$0")"
PY=F:/Pixelclub/tools/.venv/Scripts/python.exe
ROOT=/f/Pixelclub/justteams-native-rig-20261003

boot() {
  ("$PY" rig.py boot > /dev/null 2>&1 &)
  sleep 12
  for _ in $(seq 1 60); do
    "$PY" rig.py summary 2>/dev/null | grep -q -e 'native 2.5.5+pxc.1 ready' -e 'NOT READY' && break
    sleep 5
  done
  "$PY" rig.py summary | grep 'pxc-teams' | tail -2
}
stop() {
  "$PY" rig.py send stop > /dev/null
  local pid; pid=$(cat "$ROOT/qa-server.pid")
  for _ in $(seq 1 80); do tasklist //FI "PID eq $pid" | grep -q "$pid" || return 0; sleep 3; done
  echo "rig did not stop" >&2; return 1
}

for case in "$@"; do
  echo "=== $case"
  boot
  "$PY" chest_recovery.py fault "$case" 2>&1 | grep -v queued
  stop
  boot
  "$PY" chest_recovery.py recover "$case" 2>&1 | grep -v queued
  "$PY" chest_recovery.py rejoin "$case" 2>&1 | grep -v queued
  stop
done
