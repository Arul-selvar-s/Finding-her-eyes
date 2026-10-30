#!/usr/bin/env bash
# Runs inside the emulator job: install the APK, deny/grant file access, tap "Scan files",
# check that the category counts appear, keep screenshots + UI dumps + logcat.
set -uo pipefail
OUT=e2e-out; mkdir -p "$OUT"
FAIL=0
fail() { echo "E2E FAIL: $*" | tee -a "$OUT/result.txt"; FAIL=1; }
ok()   { echo "E2E ok:   $*" | tee -a "$OUT/result.txt"; }

APK=$(ls apk/*.apk | head -1)
adb wait-for-device
adb install -r "$APK" || { fail "install"; exit 1; }
adb logcat -c

# sample files on shared storage
adb shell mkdir -p /sdcard/Pictures/E2E/sub /sdcard/Download/E2E /sdcard/Music/E2E /sdcard/Movies/E2E
for i in 1 2 3 4 5; do head -c 4096 /dev/urandom > /tmp/p.bin; adb push /tmp/p.bin /sdcard/Pictures/E2E/sub/photo$i.jpg >/dev/null; done
head -c 2048 /dev/urandom > /tmp/p.bin
adb push /tmp/p.bin /sdcard/Download/E2E/report.pdf >/dev/null
adb push /tmp/p.bin /sdcard/Music/E2E/song.mp3 >/dev/null
adb push /tmp/p.bin /sdcard/Movies/E2E/clip.mp4 >/dev/null

dump()  { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot()  { adb exec-out screencap -p > "$OUT/$1.png"; }

# 1) first launch WITHOUT file access -> permission screen
adb shell am start -n app.facefiles/.MainActivity >/dev/null
sleep 8; dump ui1-permission; shot 1-permission
python3 scripts/ui_find.py "$OUT/ui1-permission.xml" "Grant file access" >/dev/null && ok "permission screen shown" || fail "permission screen missing"

# 2) grant all-files access, relaunch
adb shell appops set app.facefiles MANAGE_EXTERNAL_STORAGE allow
adb shell am force-stop app.facefiles
adb shell am start -n app.facefiles/.MainActivity >/dev/null
sleep 8; dump ui2-main; shot 2-main
XY=$(python3 scripts/ui_find.py "$OUT/ui2-main.xml" "Scan files") || { fail "main screen / Scan button missing"; XY=""; }

# 3) tap Scan files and wait
if [ -n "$XY" ]; then
  adb shell input tap $XY
  for i in $(seq 1 20); do sleep 3; dump ui3-after; python3 scripts/ui_find.py "$OUT/ui3-after.xml" "Done:" >/dev/null && break; done
  shot 3-after-scan
  python3 - "$OUT/ui3-after.xml" <<'PY' | tee -a "$OUT/result.txt"
import re, sys
import xml.etree.ElementTree as ET
texts = [n.get("text") or "" for n in ET.parse(sys.argv[1]).iter("node")]
print("UI texts:", [t for t in texts if t][:20])
def count(label):
    for t in texts:
        m = re.fullmatch(label + r" \((\d+)\)", t.strip())
        if m: return int(m.group(1))
    return None
photos, videos, audio, docs = count("Photos"), count("Videos"), count("Audio"), count("Documents")
print("counts: photos=%s videos=%s audio=%s documents=%s" % (photos, videos, audio, docs))
bad = []
if photos is None or photos < 5: bad.append("photos<5")
if videos is None or videos < 1: bad.append("videos<1")
if audio  is None or audio  < 1: bad.append("audio<1")
if docs   is None or docs   < 1: bad.append("documents<1")
if not any(t.startswith("Done:") for t in texts): bad.append("scan did not finish")
print("E2E ok:   counts as expected" if not bad else "E2E FAIL: " + ",".join(bad))
sys.exit(1 if bad else 0)
PY
  [ "${PIPESTATUS[0]}" = "0" ] || FAIL=1
fi

# 4) crash check
adb logcat -d > "$OUT/logcat.txt"
grep -n "FATAL EXCEPTION" "$OUT/logcat.txt" | head -5 && fail "app crashed (see logcat)" || ok "no crash in logcat"
adb shell pidof app.facefiles >/dev/null && ok "process alive" || fail "process not running"

echo "E2E exit=$FAIL" | tee -a "$OUT/result.txt"
exit $FAIL
