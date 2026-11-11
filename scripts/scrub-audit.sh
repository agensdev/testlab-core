#!/usr/bin/env bash
# scrub-audit.sh — fails when a tracked file (or, with --history, any commit) holds values that
# belong to one organisation or one machine. The core must stay generic: organisation-specific
# values belong in a profile repo, not here.
#   scripts/scrub-audit.sh [--history] [--list]
#
# Two word lists:
# - the generic one below: private network addresses and tailnet host names;
# - a private one that is never committed: names of the organisations, people, apps and machines
#   a maintainer works with. It is one extended regular expression (grep -E, case-insensitive),
#   read from $SCRUB_EXTRA_PATTERN, else from the file .scrub-private in the repo root (ignored
#   by git), else from ~/.config/testlab-dev/scrub-pattern. CI gets it from the repository
#   secret SCRUB_EXTRA_PATTERN; without it (a fork, say) only the generic list is checked.
set -euo pipefail
cd "$(dirname "$0")/.."
PATTERN='192\.168\.[0-9]+\.[0-9]+|\b100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+\b|\btail[0-9a-f]{4,}\.ts\.net'
extra=${SCRUB_EXTRA_PATTERN:-}
for f in .scrub-private "$HOME/.config/testlab-dev/scrub-pattern"; do
  if [ -z "$extra" ] && [ -f "$f" ]; then extra=$(grep -v '^#' "$f" | tr -d '\n'); fi
done
lists="generic + private list"
if [ -n "$extra" ]; then PATTERN="$PATTERN|$extra"; else lists="generic list only: no private word list set"; fi
# Earlier commits of this script carried the word list itself.
ALLOW='scripts/scrub-audit.sh'
mode=files; list=false
for a in "$@"; do case "$a" in --history) mode=history ;; --list) list=true ;; esac; done
if [ "$mode" = history ]; then
  hits=$(git log -p --all -i -E -G "$PATTERN" --format='commit %h' -- . ":!$ALLOW" | grep -i -E "^[+-].*($PATTERN)" | head -50 || true)
else
  # New files too, not only tracked ones: the audit runs before `git add` as well.
  files=$(git ls-files --cached --others --exclude-standard 2>/dev/null || find . -type f -not -path './.git/*' -not -path '*/build/*' -not -path './.gradle/*' | sed 's|^\./||')
  hits=$(echo "$files" | grep -v -E '\.(apk|jar|dex|png|jpg|h264|mp4)$' | xargs -d '\n' grep -I -i -n -E "$PATTERN" 2>/dev/null || true)
fi
if [ -n "$hits" ]; then
  if $list; then echo "$hits"; else echo "$hits" | head -40; echo "… $(echo "$hits" | wc -l) hit(s)"; fi
  echo "scrub-audit: FAILED" >&2; exit 1
fi
echo "scrub-audit: clean ($mode, $lists)"
