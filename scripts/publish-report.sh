#!/usr/bin/env bash
# usage: publish-report.sh <name> <file-or-dir>...
# Pushes the given files to branch `ci-reports` under reports/<sha7>/<name>/ so they can be read
# without downloading Actions artifacts. Never fails the build.
set -uo pipefail
name="$1"; shift
dest="reports/${GITHUB_SHA::7}/${name}"
url="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
work="$(mktemp -d)"

for attempt in 1 2 3 4 5; do
  rm -rf "$work/repo"
  if git ls-remote --exit-code --heads "$url" ci-reports >/dev/null 2>&1; then
    git clone -q --depth 1 --branch ci-reports "$url" "$work/repo" || { sleep 3; continue; }
  else
    git init -q "$work/repo"
    git -C "$work/repo" checkout -q -b ci-reports
    git -C "$work/repo" remote add origin "$url"
  fi
  mkdir -p "$work/repo/$dest"
  for p in "$@"; do [ -e "$p" ] && cp -r "$p" "$work/repo/$dest/"; done
  (
    cd "$work/repo"
    git config user.name "ci"; git config user.email "ci@users.noreply.github.com"
    git add -A
    git commit -q -m "CI report: ${name} @ ${GITHUB_SHA::7}" || exit 0
    git push -q origin ci-reports
  ) && exit 0
  sleep $((attempt * 2))
done
echo "report upload failed (ignored)"; exit 0
