#!/usr/bin/env bash
# Installs the newest adreno-apk build of a branch on the USB phone, and puts the previous one back if
# the new one doesn't come up. Needs gh (logged in) and adb with one phone attached by USB.
#
#   scripts/deploy-adreno.sh                       # newest green build of adreno-glass
#   scripts/deploy-adreno.sh upstream-sync/v0.7.3  # a pull request's build
#   scripts/deploy-adreno.sh --rollback            # back to the APK installed before the last deploy
#
# Installing over the app keeps its data (Linux, layout, widgets), so only the APK needs a way back: the
# installed one is saved in ~/omalauncher-backups/deploy first. Widget saves live in Linux
# (~/.config/omalauncher/widgets/.state), where ~/backup.sh keeps them.
set -euo pipefail

REPO=Druspect/omalauncher
PKG=dev.omalauncher.adreno
KEEP=~/omalauncher-backups/deploy
mkdir -p "$KEEP"
chmod 700 "$KEEP"

die() { echo "!! $*" >&2; exit 1; }

[ -n "${ANDROID_SERIAL:-}" ] || [ "$(adb devices | grep -c "device$")" = 1 ] || die "attach one phone by USB, or set ANDROID_SERIAL"
adb get-serialno | grep -q ':' && die "that's wireless adb; use USB"

version() { adb shell dumpsys package "$PKG" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r'; }

# Up: the process runs, nothing of ours crashed since the install, and Linux is back if it was running before.
healthy() {
  local linux_before=$1
  for _ in $(seq 1 30); do
    adb shell pidof "$PKG" >/dev/null && break
    sleep 1
  done
  sleep 10
  adb shell pidof "$PKG" >/dev/null || { echo "process isn't running"; return 1; }
  if adb logcat -d -b crash | grep -q "Process: $PKG"; then
    adb logcat -d -b crash | grep -A8 "Process: $PKG" | head -20
    return 1
  fi
  if [ "$linux_before" = 1 ] && ! adb shell dumpsys activity services "$PKG" | grep -q "$PKG/dev.omalauncher.LinuxService"; then
    echo "Linux didn't come back"; return 1
  fi
}

install_and_check() { # apk
  local linux_before=0
  adb shell dumpsys activity services "$PKG" | grep -q "$PKG/dev.omalauncher.LinuxService" && linux_before=1
  adb logcat -c -b crash
  adb install -r "$1" | tail -1
  adb shell input keyevent KEYCODE_HOME
  healthy "$linux_before"
}

if [ "${1:-}" = --rollback ]; then
  [ -f "$KEEP/last-good.apk" ] || die "nothing to roll back to"
  install_and_check "$KEEP/last-good.apk" && echo "back on $(version)" || die "the previous build doesn't come up either"
  exit 0
fi

branch=${1:-adreno-glass}
run=$(gh run list -R "$REPO" -w adreno-apk.yml -b "$branch" -s success -L 1 --json databaseId -q '.[0].databaseId')
[ -n "$run" ] || die "no green adreno-apk build of $branch"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh run download "$run" -R "$REPO" -n omalauncher-adreno -D "$work"
(cd "$work" && grep omalauncher-adreno.apk SHA256SUMS | shasum -a 256 -c -) || die "checksum mismatch"

# The installed APK, for the way back.
old=$(version)
if [ -n "$old" ]; then
  adb pull "$(adb shell pm path "$PKG" | sed -n 's/^package://p' | head -1 | tr -d '\r')" "$KEEP/$old.apk" >/dev/null
  ln -sf "$KEEP/$old.apk" "$KEEP/last-good.apk"
fi

echo "== $old -> build $run of $branch"
if install_and_check "$work/omalauncher-adreno.apk"; then
  echo "ok: $(version) is up"
else
  [ -n "$old" ] || die "it doesn't come up, and there was nothing installed before"
  echo "!! it doesn't come up; putting $old back"
  # Android refuses a lower versionCode on a release build: across an upstream release, roll back by hand.
  install_and_check "$KEEP/$old.apk" && die "rolled back to $old" || die "rollback failed too; see above"
fi
