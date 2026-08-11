#!/usr/bin/env bash
#
# Prints the release notes for one version: its section of the changelog, followed by
# the compare link the changelog already records for it.
#
# GitHub's --generate-notes lists merged pull request titles. That records what landed,
# not what changed for anyone installing the plugin, and it appends a "New Contributors"
# block that says nothing on a single-author repository. The changelog already describes
# the change properly, so the release quotes it rather than telling a second, thinner
# story beside it.
#
#   release-notes.sh 0.2.3 [CHANGELOG.md]
#
# Writes nothing and fails when the section is missing or empty, for the same reason
# cut-changelog.sh refuses an empty [Unreleased]: notes that say nothing are worse than
# no notes, because they look answered.
set -euo pipefail

version=${1:?usage: release-notes.sh <version> [file]}
file=${2:-CHANGELOG.md}

fail() {
    echo "$*" >&2
    exit 1
}

case "$version" in
    v*) fail "Pass the version without the leading v, got '$version'." ;;
esac
# The same check cut-changelog.sh makes, and for a sharper reason: every heading in the
# file is a valid argument here, so without it a typo does not fail -- it quietly returns
# the notes of some other section. 'Unreleased' is the one that would really happen.
printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$' ||
    fail "'$version' is not a version; expected MAJOR.MINOR.PATCH."
[ -f "$file" ] || fail "No such file: $file"

# Headings are matched as a literal prefix rather than as a regex: they contain brackets
# and dots, and escaping those correctly for every caller is a bug waiting to happen.
#
# A section ends at the next heading or at the block of link definitions -- the oldest
# version is last in the file and has no heading after it, so stopping only at '## ' would
# append the whole link block to its notes.
body=$(awk -v want="## [$version]" '
    index($0, want) == 1 { inside = 1; next }
    inside && (/^## / || /^\[[^]]+\]: /) { exit }
    inside { print }
' "$file")

[ -n "$(printf '%s' "$body" | tr -d '[:space:]')" ] ||
    fail "No '## [$version]' section in $file, or it is empty."

# The section is bounded by a blank line on each side; the ones inside it are part of the
# prose and stay.
printf '%s\n' "$body" | awk '
    { line[NR] = $0 }
    END {
        first = 1
        while (first <= NR && line[first] ~ /^[[:space:]]*$/) first++
        last = NR
        while (last >= first && line[last] ~ /^[[:space:]]*$/) last--
        for (i = first; i <= last; i++) print line[i]
    }
'

# Read out of the file rather than composed from the version, so it cannot disagree with
# the links the changelog already publishes.
link=$(awk -v want="[$version]: " '
    index($0, want) == 1 { print substr($0, length(want) + 1); exit }
' "$file")

# Printed only when it is a comparison. The first release has no predecessor and its link
# points at its own tag, which on its own release page is a link back to where you are.
case "$link" in
    *"/compare/"*) printf '\n**Full changelog**: %s\n' "$link" ;;
esac

exit 0
