#!/usr/bin/env bash
# Reads and changes the NewPipeExtractor version in gradle/libs.versions.toml (used by the "Self-heal YouTube" workflow).
#   newpipe.sh current          prints the version now in use, e.g. v0.26.5
#   newpipe.sh set v0.26.6      writes that version (must look like v1.2.3)
#   newpipe.sh newer v0.26.5 v0.26.6   exit code 0 when the second version is newer than the first
set -euo pipefail
TOML="${TOML:-gradle/libs.versions.toml}"
cmd="${1:-}"
case "$cmd" in
  current)
    sed -n 's/^newpipe *= *"\(.*\)".*/\1/p' "$TOML" | head -1
    ;;
  set)
    tag="${2:?usage: newpipe.sh set v1.2.3}"
    [[ "$tag" =~ ^v?[0-9]+(\.[0-9]+)*$ ]] || { echo "not a version: $tag" >&2; exit 2; }
    sed -i "s/^newpipe *= *\".*\"/newpipe = \"$tag\"/" "$TOML"
    "$0" current
    ;;
  newer)
    a="${2#v}"; b="${3#v}"
    [ -n "$a" ] && [ -n "$b" ] && [ "$a" != "$b" ] && [ "$(printf '%s\n%s\n' "$a" "$b" | sort -V | tail -1)" = "$b" ]
    ;;
  *)
    echo "usage: newpipe.sh current | set <version> | newer <old> <new>" >&2
    exit 2
    ;;
esac
