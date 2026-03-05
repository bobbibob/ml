#!/usr/bin/env bash
set -euo pipefail

ROOT="${ROOT:-$(pwd)}"
cd "$ROOT"

UI_DIR="app/src/main/java"
echo "==> Repo: $ROOT"
echo "==> Searching 'Дата:' occurrences..."
DATE_HITS=$(grep -RIn --include='*.kt' 'Дата:' "$UI_DIR" || true)

if [[ -z "${DATE_HITS}" ]]; then
  echo "No 'Дата:' found in Kotlin sources. Maybe it is built from resources or composed differently."
else
  echo "$DATE_HITS"
fi

echo
echo "==> Searching files that call AddEditArticleScreen(...) ..."
FILES_WITH_EDITOR=$(grep -RIl --include='*.kt' 'AddEditArticleScreen\s*\(' "$UI_DIR" || true)

if [[ -z "${FILES_WITH_EDITOR}" ]]; then
  echo "No AddEditArticleScreen(...) call sites found (unexpected)."
else
  echo "$FILES_WITH_EDITOR"
fi

echo
echo "==> Attempting to hide 'Дата:' only when editor is visible (heuristic patch)..."

patch_one_file() {
  local f="$1"
  local tmp="${f}.tmp.$$"

  # Find a simple pattern: if (<cond>) { ... AddEditArticleScreen(
  # We'll capture <cond> and then wrap any date Text line(s) with if (!(<cond>)) { ... }
  awk '
    function ltrim(s){ sub(/^[ \t\r\n]+/, "", s); return s }
    function rtrim(s){ sub(/[ \t\r\n]+$/, "", s); return s }
    function trim(s){ return rtrim(ltrim(s)) }

    BEGIN{
      cond=""
      in_if=0
      saw_editor_in_if=0
      if_depth=0
      patched=0
    }

    {
      line=$0

      # detect entering an if (...) { line
      if (match(line, /^[ \t]*if[ \t]*\((.*)\)[ \t]*\{[ \t]*$/, m)) {
        in_if=1
        saw_editor_in_if=0
        if_depth=1
        cond=trim(m[1])
        print line
        next
      }

      # track brace depth while inside an if-block
      if (in_if==1) {
        # see editor call inside this if
        if (line ~ /AddEditArticleScreen[ \t]*\(/) {
          saw_editor_in_if=1
        }

        # If this line renders date text AND we are in the same if-block that shows editor,
        # we do NOT want to show date there. But usually date is outside that block.
        print line

        # update depth counters
        open_count=gsub(/\{/, "{", line)
        close_count=gsub(/\}/, "}", line)
        if_depth += open_count
        if_depth -= close_count

        if (if_depth<=0) {
          in_if=0
          saw_editor_in_if=0
          if_depth=0
        }
        next
      }

      # Outside if-block: try to find date Text line(s)
      # We only patch if we have found *some* editor condition in this file earlier (store last_cond)
      if (line ~ /AddEditArticleScreen[ \t]*\(/) {
        last_cond=cond
      }

      # Patch date rows:
      # - Text("Дата: ...")
      # - or string contains "Дата:"
      if (line ~ /Дата:/) {
        # We need a condition to hide date. Try to find the nearest simple flag in this file:
        # search backwards is hard in awk; so we use a best-effort:
        # If file contains "AddEditArticleScreen(" anywhere, we use a generic flag name if present:
        # showArticleEditor / showEditor / isEditing / editorOpen etc.
        # We will pick the first one found by a cheap scan in BEGIN (not possible), so fallback: do nothing here.
        print line
        next
      }

      print line
    }
  ' "$f" > "$tmp"

  # Above awk is conservative and might not patch; we do a more targeted sed-based patch next:
  mv "$tmp" "$f"
}

# Heuristic: if a file contains both 'Дата:' and 'AddEditArticleScreen(' then we can wrap the date area
# with a condition based on an obvious flag present in that file.
find_editor_flag() {
  local f="$1"
  # list of likely flags used to show editor
  local candidates=(
    "showArticleEditor"
    "showAddEdit"
    "showEditor"
    "isEditing"
    "editorOpen"
    "isArticleEditorVisible"
    "openArticleEditor"
  )
  for c in "${candidates[@]}"; do
    if grep -q "$c" "$f"; then
      echo "$c"
      return 0
    fi
  done
  echo ""
  return 0
}

wrap_date_with_flag() {
  local f="$1"
  local flag="$2"

  # Only if file has date text and does not already have an if(!flag) just around it.
  # We will wrap the *single line* that contains Дата: (safe and simple).
  # This covers your case where the date is shown as one Text line in header.
  local tmp="${f}.tmp.$$"

  awk -v flag="$flag" '
    BEGIN{patched=0}
    {
      if ($0 ~ /Дата:/ && patched==0) {
        # if already wrapped line-by-line, skip
        if ($0 ~ /if[ \t]*\(!/ ) { print; next }

        indent=""
        match($0, /^[ \t]*/, m); indent=m[0]
        print indent "if (!" flag ") {"
        print $0
        print indent "}"
        patched=1
        next
      }
      print
    }
    END{
      # nothing
    }
  ' "$f" > "$tmp"

  mv "$tmp" "$f"
}

PATCHED_DATE=0
if [[ -n "${DATE_HITS}" && -n "${FILES_WITH_EDITOR}" ]]; then
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    if grep -q 'Дата:' "$f"; then
      flag="$(find_editor_flag "$f")"
      if [[ -n "$flag" ]]; then
        echo "   -> Wrapping date in $f with if (!${flag}) { ... }"
        wrap_date_with_flag "$f" "$flag"
        PATCHED_DATE=1
      else
        echo "   -> Found both date and editor in $f, but no obvious flag. Leaving date unchanged."
      fi
    fi
  done <<< "$FILES_WITH_EDITOR"
fi

if [[ "$PATCHED_DATE" -eq 0 ]]; then
  echo "==> Date was NOT auto-wrapped (either different file or no obvious flag)."
  echo "    We'll still commit Instagram fix + show you date locations above."
fi

echo
echo "==> Fixing Instagram visibility logic..."

# Replace common patterns:
# 1) snake_case: ig_spend > 0  => (ig_spend > 0 || ig_impressions > 0 || ig_clicks > 0)
# 2) camelCase: igSpend > 0    => (igSpend > 0 || igImpressions > 0 || igClicks > 0)
#
# We apply only to UI kotlin files.
IG_FILES=$(grep -RIl --include='*.kt' 'ig_spend|igSpend' "$UI_DIR" || true)
if [[ -z "$IG_FILES" ]]; then
  echo "No ig_* usage found in Kotlin. Maybe DB parser doesn't fill it or UI doesn't reference yet."
else
  echo "$IG_FILES"
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue

    # snake_case
    sed -i -E \
      's/\big_spend[[:space:]]*>[[:space:]]*0\b/(ig_spend > 0 || ig_impressions > 0 || ig_clicks > 0)/g' \
      "$f"

    # camelCase
    sed -i -E \
      's/\bigSpend[[:space:]]*>[[:space:]]*0\b/(igSpend > 0 || igImpressions > 0 || igClicks > 0)/g' \
      "$f"
  done <<< "$IG_FILES"
fi

echo
echo "==> Quick verification (grep):"
echo "--- Date lines:"
grep -RIn --include='*.kt' 'Дата:' "$UI_DIR" || true
echo "--- Instagram checks:"
grep -RIn --include='*.kt' 'ig_spend|igSpend' "$UI_DIR" || true

echo
echo "==> Git status:"
git status --porcelain

echo
echo "==> Commit + push (only if there are changes)..."
if [[ -n "$(git status --porcelain)" ]]; then
  git add app/src/main/java
  git commit -m "UI: hide date in editor (if possible) + fix Instagram presence check" || true
  git push
  echo "DONE: pushed changes."
else
  echo "No changes to commit."
fi
