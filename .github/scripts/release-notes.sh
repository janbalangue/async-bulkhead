#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version, to use as GitHub Release notes.
# Fails if the section is missing or empty.
set -euo pipefail

version="${1:?usage: release-notes.sh <version>}"

notes="$(awk -v heading="## [$version]" '
  index($0, heading) == 1 { found = 1; print; next }
  found && /^## \[/ { exit }
  found { print }
' CHANGELOG.md)"

# Drop the separator before the next section.
notes="$(printf '%s\n' "$notes" | sed -e '$ { /^---$/d; }')"

if [[ "$notes" != *$'\n'* ]]; then
  echo "❌ No notes for $version in CHANGELOG.md" >&2
  exit 1
fi

printf '%s\n' "$notes"
