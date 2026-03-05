#!/usr/bin/env bash
set -euo pipefail

echo "==> branch:"
git rev-parse --abbrev-ref HEAD

echo "==> last commit:"
git --no-pager log -1 --oneline

echo "==> Creating empty commit to trigger GitHub Actions..."
git commit --allow-empty -m "CI: trigger build" >/dev/null

echo "==> Pushing..."
git push

echo "DONE: pushed empty commit. Проверь вкладку Actions на GitHub."
