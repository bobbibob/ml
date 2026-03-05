#!/usr/bin/env bash
set -euo pipefail

WF=".github/workflows/android.yml"

echo "==> Branch:"
git rev-parse --abbrev-ref HEAD

echo "==> HEAD:"
git --no-pager log -1 --oneline

echo
echo "==> Check workflow file exists in THIS commit:"
if git cat-file -e "HEAD:$WF" 2>/dev/null; then
  echo "OK: $WF exists in HEAD"
else
  echo "ERROR: $WF NOT FOUND in HEAD."
  echo "Actions won't run on this branch until the workflow file is present here."
  echo "Fix: merge/copy workflow into stable_build and push."
  exit 1
fi

echo
echo "==> Show workflow triggers (first ~80 lines):"
git show "HEAD:$WF" | sed -n '1,120p'

echo
echo "==> Quick scan: on/paths/branches:"
HAS_PATHS="0"
if git show "HEAD:$WF" | grep -nE '^[[:space:]]*paths:' >/dev/null; then
  HAS_PATHS="1"
  echo "FOUND: paths: filter is present -> empty commits may NOT trigger builds."
  echo "paths lines:"
  git show "HEAD:$WF" | grep -nE '^[[:space:]]*paths:|^[[:space:]]*-[[:space:]]' | sed -n '1,80p' || true
else
  echo "OK: no paths: filter found."
fi

echo
echo "==> Trigger strategy:"
if [[ "$HAS_PATHS" == "1" ]]; then
  echo "Creating a tiny change under app/ to satisfy paths filter..."
  mkdir -p app
  date -u +"%Y-%m-%dT%H:%M:%SZ" > app/.ci_trigger
  git add app/.ci_trigger
  git commit -m "CI: trigger build (touch app/.ci_trigger)"
  git push
  echo "DONE: pushed change under app/.ci_trigger"
else
  echo "No paths filter -> an empty commit SHOULD have triggered."
  echo "If there is still no run, likely Actions are disabled or restricted in repo settings."
  echo "Check on GitHub: Settings -> Actions -> General -> allow Actions + allow workflows."
  echo "Also check Actions tab for messages like 'Actions are disabled'."
fi
