#!/usr/bin/env bash
# Builds every CLI plugin package into one shelf: cli-packages/dist/.
#
# The shelf is a drop box as much as a build output. Anything you put there by hand
# (`cli-packages/dist/mycli-1.2.0.harnaxcli.zip`) ships: the docker-new scripts copy the whole shelf
# into the admin image and a bind-mounted admin registers it at startup. What this script adds is the
# packages that have a source in this repository — `harnax-cli`, which packs itself with its own
# Makefile, plus every third-party CLI that has a directory here (`plugin.yaml`, `skill/`, and a
# `build.sh` that fetches the binary and packs it).
#
#   ./build.sh                      # every package with a source, hand drops left alone
#   ARCH=arm64 ./build.sh           # forwarded to the third-party builders
#   ONLY=lark-cli ./build.sh        # one third-party package
#
# So the shelf is never cleared. A package that cleared it would take every hand drop with it, and a
# missing package is not "nothing happened" to admin — it is "this CLI was retired", and the row gets
# pruned. One CLI name still means one package on the shelf: when two files declare the same name the
# higher manifest version wins and the loser moves to dist/.superseded/ (moved, never deleted, since
# a hand drop has no source to rebuild it from). That is the same rule admin applies, applied earlier
# so it never has to.
set -euo pipefail

cd "$(dirname "$0")"
SHELF=dist
HARNAX_CLI=../harnax-cli
SUPERSEDED="$SHELF/.superseded"

manifest_field() {
  unzip -p "$1" plugin.yaml | awk -F': *' -v key="$2" '$1 == key {print $2; exit}' | tr -d ' \r'
}
manifest_name() { manifest_field "$1" name; }
manifest_version() { manifest_field "$1" version; }

if [ -n "${ONLY:-}" ]; then
  [ -f "$ONLY/build.sh" ] || { echo "ERROR: ONLY=$ONLY but cli-packages/$ONLY/build.sh does not exist" >&2; exit 1; }
  echo "building cli-packages/$ONLY only"
  (cd "$ONLY" && ./build.sh)
else
  mkdir -p "$SHELF"
  if command -v go >/dev/null 2>&1; then
    (cd "$HARNAX_CLI" && make package >/dev/null)
    built=( "$HARNAX_CLI"/dist/*.harnaxcli.zip )
    # Exactly one, or two packages of the same CLI name land on the shelf at once and the newest one
    # wins by manifest version — which is not the same as which one this build just produced.
    # An unmatched glob leaves bash with the pattern as its single element, so -f is the real test.
    if [ "${#built[@]}" -ne 1 ] || [ ! -f "${built[0]:-}" ]; then
      echo "ERROR: expected exactly one package in harnax-cli/dist, found: ${built[*]:-none}" >&2
      exit 1
    fi
    cp "${built[0]}" "$SHELF/"
    echo "  ✓ $(basename "${built[0]}")"
  elif ls "$SHELF"/harnax-*.harnaxcli.zip >/dev/null 2>&1; then
    # The platform's own CLI is already on the shelf from an earlier build or a hand drop, so the
    # shelf still carries it and nothing gets pruned. It just won't be a fresh build.
    echo "WARN: go not installed — keeping the harnax package already on the shelf instead of rebuilding it" >&2
  else
    # No go and no harnax package: that shelf would make admin prune the harnax row, so it is a
    # failure, not a degraded build.
    echo "ERROR: go not installed and cli-packages/$SHELF holds no harnax package — admin would read" >&2
    echo "       the missing package as a retirement and prune the CLI row. Install go, or drop a" >&2
    echo "       harnax-<version>.harnaxcli.zip on the shelf deliberately." >&2
    exit 1
  fi

  shopt -s nullglob
  for spec in */build.sh; do
    pkg_dir=$(dirname "$spec")
    echo "building cli-packages/$pkg_dir"
    (cd "$pkg_dir" && ./build.sh)
  done
fi

shopt -s nullglob
packages=( "$SHELF"/*.harnaxcli.zip )
if [ "${#packages[@]}" -eq 0 ]; then
  echo "ERROR: cli-packages/$SHELF is empty — admin would start with no CLI registered" >&2
  exit 1
fi

# One name, one package: keep the highest manifest version, move the rest aside.
declared=$(
  for pkg in "${packages[@]}"; do
    printf '%s\t%s\t%s\n' "$(manifest_name "$pkg")" "$(manifest_version "$pkg")" "$pkg"
  done
)
for name in $(printf '%s\n' "$declared" | cut -f1 | sort -u); do
  same_name=$(printf '%s\n' "$declared" | awk -F'\t' -v n="$name" '$1 == n')
  [ "$(printf '%s\n' "$same_name" | wc -l | tr -d ' ')" -gt 1 ] || continue
  tied=$(printf '%s\n' "$same_name" | cut -f2 | sort | uniq -d)
  if [ -n "$tied" ]; then
    echo "ERROR: several packages declare the same CLI name at the same version:" >&2
    for version in $tied; do
      printf "         %s %s:\n" "$name" "$version" >&2
      printf '%s\n' "$same_name" | awk -F'\t' -v v="$version" '$2 == v {print "           " $3}' >&2
    done
    echo "       nothing here says which one to register, so the shelf refuses instead of leaving that" >&2
    echo "       to admin (which would then register neither and count both as failures)." >&2
    exit 1
  fi
  winner=$(printf '%s\n' "$same_name" | sort -t $'\t' -k2,2V | tail -1 | cut -f3)
  mkdir -p "$SUPERSEDED"
  for pkg in $(printf '%s\n' "$same_name" | cut -f3); do
    [ "$pkg" = "$winner" ] && continue
    mv -f "$pkg" "$SUPERSEDED/"
    echo "WARN: '$name' is on the shelf twice — keeping $(basename "$winner") and moving $(basename "$pkg") to $SUPERSEDED/" >&2
  done
done

packages=( "$SHELF"/*.harnaxcli.zip )
echo "cli-packages/$SHELF:"
for pkg in "${packages[@]}"; do
  printf '  %s (%s bytes, name=%s, version=%s)\n' \
    "$(basename "$pkg")" "$(wc -c < "$pkg" | tr -d ' ')" "$(manifest_name "$pkg")" "$(manifest_version "$pkg")"
done
