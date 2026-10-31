#!/usr/bin/env bash
# Downloads the two ONNX face models (open licences) and places them where both apps bundle them:
#   Android -> composeApp/src/androidMain/assets/models
#   Desktop -> composeApp/src/desktopMain/resources/models
#   YuNet face detector  (OpenCV Zoo, MIT)         ~230 KB
#   SFace face embedder  (OpenCV Zoo, Apache-2.0)  ~37 MB, 128-d output
set -euo pipefail
cd "$(dirname "$0")/.."
D=composeApp/src/desktopMain/resources/models
A=composeApp/src/androidMain/assets/models
mkdir -p "$D" "$A"
BASE=https://github.com/opencv/opencv_zoo/raw/main/models

fetch() {
  local name="$1" url="$2"
  if [ ! -s "$D/$name" ]; then
    echo "downloading $name"
    curl -fL --retry 4 --retry-delay 3 -o "$D/$name" "$url"
  fi
  # git-lfs pointer files are tiny text files, a real model is binary protobuf
  if head -c 64 "$D/$name" | grep -q "git-lfs"; then
    echo "ERROR: $name is a git-lfs pointer, not the model" >&2; rm -f "$D/$name"; exit 1
  fi
  cp -f "$D/$name" "$A/$name"
  echo "$(sha256sum "$D/$name" | cut -d' ' -f1)  $(wc -c < "$D/$name") bytes  $name"
}

fetch face_detection_yunet_2023mar.onnx  "$BASE/face_detection_yunet/face_detection_yunet_2023mar.onnx"
fetch face_recognition_sface_2021dec.onnx "$BASE/face_recognition_sface/face_recognition_sface_2021dec.onnx"
