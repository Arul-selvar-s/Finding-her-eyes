#!/usr/bin/env bash
# Downloads a few public sample portraits used ONLY by the automated tests (never bundled in the app).
set -euo pipefail
cd "$(dirname "$0")/.."
T=composeApp/src/desktopTest/resources/faces
mkdir -p "$T"
BASE=https://raw.githubusercontent.com/ageitgey/face_recognition/master/examples
for f in obama.jpg obama2.jpg biden.jpg alex-lacamoire.png lin-manuel-miranda.png two_people.jpg; do
  [ -s "$T/$f" ] || curl -fL --retry 4 --retry-delay 3 -o "$T/$f" "$BASE/$f"
  echo "$(wc -c < "$T/$f") bytes  $f"
done
