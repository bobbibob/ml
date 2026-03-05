#!/usr/bin/env bash
set -euo pipefail

ZIP="${1:-database_pack.zip}"

if [[ ! -f "$ZIP" ]]; then
  echo "ERROR: not found: $ZIP"
  echo "Usage: bash tools/diag_instagram.sh path/to/database_pack.zip"
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

unzip -q "$ZIP" -d "$WORK"
DB="$WORK/data.sqlite"

if [[ ! -f "$DB" ]]; then
  echo "ERROR: data.sqlite not found inside $ZIP"
  exit 1
fi

echo "==> PRAGMA table_info(svodka)"
sqlite3 "$DB" "PRAGMA table_info(svodka);" | sed -n '1,120p'

echo
echo "==> Check ig_* columns exist"
COLS="$(sqlite3 "$DB" "PRAGMA table_info(svodka);" | cut -d'|' -f2)"
echo "$COLS" | grep -E '^ig_(spend|impressions|clicks|ctr|cpc)$' || true

echo
echo "==> Sample TOTAL rows (first 5)"
sqlite3 -header -column "$DB" "
SELECT date, bag, color,
       rk_spend, rk_impressions, rk_clicks,
       ig_spend, ig_impressions, ig_clicks
FROM svodka
WHERE color='__TOTAL__'
ORDER BY date DESC, bag
LIMIT 5;
"

echo
echo "==> Any IG activity? (ig_spend>0 OR ig_impressions>0 OR ig_clicks>0)"
sqlite3 -header -column "$DB" "
SELECT date, bag, ig_spend, ig_impressions, ig_clicks
FROM svodka
WHERE color='__TOTAL__'
  AND (ig_spend>0 OR ig_impressions>0 OR ig_clicks>0)
ORDER BY date, bag
LIMIT 50;
"

echo
echo "==> Count TOTAL rows with any IG activity"
sqlite3 "$DB" "
SELECT COUNT(*)
FROM svodka
WHERE color='__TOTAL__'
  AND (ig_spend>0 OR ig_impressions>0 OR ig_clicks>0);
"
