#!/usr/bin/env bash
# Runs inside the emulator job: install the APK, check the permission screen, grant file access,
# tap "Scan files", then "Find faces", open a portrait, tap the face and check the person search.
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
adb shell mkdir -p /sdcard/Pictures/E2E/sub /sdcard/Pictures/E2E/people /sdcard/Download/E2E /sdcard/Music/E2E /sdcard/Movies/E2E
for i in 1 2 3 4 5; do head -c 4096 /dev/urandom > /tmp/p.bin; adb push /tmp/p.bin /sdcard/Pictures/E2E/sub/photo$i.jpg >/dev/null; done
head -c 2048 /dev/urandom > /tmp/p.bin
adb push /tmp/p.bin /sdcard/Download/E2E/report.pdf >/dev/null
adb push /tmp/p.bin /sdcard/Music/E2E/song.mp3 >/dev/null
adb push /tmp/p.bin /sdcard/Movies/E2E/clip.mp4 >/dev/null
# real portraits for the face part (downloaded by scripts/fetch-test-data.sh)
for f in obama.jpg obama2.jpg biden.jpg two_people.jpg; do
  adb push "composeApp/src/desktopTest/resources/faces/$f" /sdcard/Pictures/E2E/people/$f >/dev/null
done
adb shell pm grant app.facefiles android.permission.POST_NOTIFICATIONS

dump()  { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot()  { adb exec-out screencap -p > "$OUT/$1.png"; }
find_xy() { python3 scripts/ui_find.py "$OUT/$1.xml" "$2"; }

# 1) first launch WITHOUT file access -> permission screen
adb shell am start -n app.facefiles/.MainActivity >/dev/null
sleep 8; dump ui1-permission; shot 1-permission
find_xy ui1-permission "Grant file access" >/dev/null && ok "permission screen shown" || fail "permission screen missing"

# 2) grant all-files access, relaunch
adb shell appops set app.facefiles MANAGE_EXTERNAL_STORAGE allow
adb shell am force-stop app.facefiles
adb shell am start -n app.facefiles/.MainActivity >/dev/null
sleep 8; dump ui2-main; shot 2-main
XY=$(find_xy ui2-main "Scan files") || { fail "main screen / Scan button missing"; XY=""; }

# 3) tap Scan files and wait
if [ -n "$XY" ]; then
  adb shell input tap $XY
  for i in $(seq 1 20); do sleep 3; dump ui3-after; find_xy ui3-after "Done:" >/dev/null && break; done
  shot 3-after-scan
  python3 - "$OUT/ui3-after.xml" <<'PYEOF' | tee -a "$OUT/result.txt"
import re, sys
import xml.etree.ElementTree as ET
texts = [n.get("text") or "" for n in ET.parse(sys.argv[1]).iter("node")]
print("UI texts:", [t for t in texts if t][:24])
def count(label):
    for t in texts:
        m = re.fullmatch(label + r" \((\d+)\)", t.strip())
        if m: return int(m.group(1))
    return None
photos, videos, audio, docs = count("Photos"), count("Videos"), count("Audio"), count("Documents")
print("counts: photos=%s videos=%s audio=%s documents=%s" % (photos, videos, audio, docs))
bad = []
if photos is None or photos < 9: bad.append("photos<9 (5 random + 4 portraits)")
if videos is None or videos < 1: bad.append("videos<1")
if audio  is None or audio  < 1: bad.append("audio<1")
if docs   is None or docs   < 1: bad.append("documents<1")
if not any(t.startswith("Done:") for t in texts): bad.append("scan did not finish")
print("E2E ok:   counts as expected" if not bad else "E2E FAIL: " + ",".join(bad))
sys.exit(1 if bad else 0)
PYEOF
  [ "${PIPESTATUS[0]}" = "0" ] || FAIL=1

  # 4) faces
  FXY=$(find_xy ui3-after "Find faces") || FXY=""
  if [ -z "$FXY" ]; then
    fail "Find faces button missing"
  else
    adb shell input tap $FXY
    for i in $(seq 1 60); do sleep 4; dump ui4-faces; find_xy ui4-faces "Faces: done" >/dev/null && break; done
    shot 4-faces-done
    if find_xy ui4-faces "Faces: done" >/dev/null; then ok "face scan finished"; else fail "face scan did not finish"; fi
    python3 - "$OUT/ui4-faces.xml" <<'PYEOF' | tee -a "$OUT/result.txt"
import sys
import xml.etree.ElementTree as ET
t = [n.get("text") or "" for n in ET.parse(sys.argv[1]).iter("node")]
print("face status:", [x for x in t if "ace" in x][:4])
PYEOF
    # open obama.jpg from the grid
    OXY=$(find_xy ui4-faces "obama.jpg") || OXY=""
    if [ -z "$OXY" ]; then
      fail "obama.jpg tile not found in the Photos grid"
    else
      adb shell input tap $OXY; sleep 4; dump ui5-viewer; shot 5-viewer
      FACE=$(find_xy ui5-viewer "Face") || FACE=""
      if [ -z "$FACE" ]; then
        fail "no face thumbnail in the viewer"
      else
        ok "viewer shows detected face"
        adb shell input tap $FACE; sleep 5; dump ui6-results; shot 6-results
        python3 - "$OUT/ui6-results.xml" <<'PYEOF' | tee -a "$OUT/result.txt"
import re, sys
import xml.etree.ElementTree as ET
t = [n.get("text") or "" for n in ET.parse(sys.argv[1]).iter("node")]
m = [re.match(r"(\d+) items with", x) for x in t]
m = [x for x in m if x]
n = int(m[0].group(1)) if m else -1
print("person results:", n, "items")
# obama.jpg, obama2.jpg and two_people.jpg contain him; biden.jpg and the random files must not
print("E2E ok:   person search returned 3 items" if n == 3 else "E2E FAIL: expected 3 items for this person, got %d" % n)
sys.exit(0 if n == 3 else 1)
PYEOF
        [ "${PIPESTATUS[0]}" = "0" ] || FAIL=1
      fi
    fi
  fi
fi

# 5) crash check
adb logcat -d > "$OUT/logcat.txt"
grep -n "FATAL EXCEPTION" "$OUT/logcat.txt" | head -5 && fail "app crashed (see logcat)" || ok "no crash in logcat"
adb shell pidof app.facefiles >/dev/null && ok "process alive" || fail "process not running"

echo "E2E exit=$FAIL" | tee -a "$OUT/result.txt"
exit $FAIL
