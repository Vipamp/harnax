#!/usr/bin/env bash
# Packs the upstream Lark/Feishu CLI into a Harnax CLI plugin package.
#
# The binary is fetched, never vendored: it is 45 MB per architecture and moves every week upstream.
# Integrity comes from checksums.txt, which is upstream's published list filtered to linux, so a
# rotated release fails this script instead of silently shipping different bytes.
#
#   ./build.sh                 # linux/amd64, matching harnax-cli's own payload architecture
#   ARCH=arm64 ./build.sh
#
# Output: cli-packages/dist/lark-cli-<version>.harnaxcli.zip — the shelf admin's image is built
# from. Run it directly or let ../build.sh drive every package at once. Download cache and the
# staging tree stay under dist/.work/, so all of it sits behind the repo's `dist/` ignore rule.
set -euo pipefail

cd "$(dirname "$0")"

NAME=$(awk -F': *' '/^name:/{print $2; exit}' plugin.yaml)
VERSION=$(awk -F': *' '/^version:/{print $2; exit}' plugin.yaml)
ARCH="${ARCH:-amd64}"
REPO="larksuite/cli"
ASSET="${NAME}-${VERSION}-linux-${ARCH}.tar.gz"
MIRROR_URL="https://registry.npmmirror.com/-/binary/${NAME}/v${VERSION}/${ASSET}"
GITHUB_URL="https://github.com/${REPO}/releases/download/v${VERSION}/${ASSET}"
SHELF=$(cd .. && pwd)/dist
WORK="${SHELF}/.work/${NAME}"
CACHE="${WORK}/${ASSET}"
STAGE="${WORK}/stage"
PKG="${SHELF}/${NAME}-${VERSION}.harnaxcli.zip"
# Upstream installs the CLI under its own prefix; the container path is what the package declares.
BIN_DEST="payload/usr/local/bin/${NAME}"
DOC_DEST="payload/usr/local/share/doc/${NAME}"

ExpectedSha() {
  awk -v asset="$ASSET" '$2 == asset {print $1}' checksums.txt
}

ShaOf() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

EXPECTED=$(ExpectedSha)
if [ -z "$EXPECTED" ]; then
  echo "checksums.txt has no linux/${ARCH} entry for ${ASSET} — add it, or build with ARCH=amd64|arm64" >&2
  exit 1
fi

mkdir -p "$SHELF" "$WORK"
if [ -f "$CACHE" ] && [ "$(ShaOf "$CACHE")" = "$EXPECTED" ]; then
  echo "cache hit: ${ASSET}"
else
  rm -f "$CACHE"
  # Mirror first: this host cannot reach github.com, and npmmirror is what the official installer
  # itself falls back to. Both sources are checked against the same pinned digest below.
  for url in "$MIRROR_URL" "$GITHUB_URL"; do
    echo "downloading ${url}"
    if curl -fsSL --max-time 600 -o "$CACHE" "$url" && [ "$(ShaOf "$CACHE")" = "$EXPECTED" ]; then
      break
    fi
    rm -f "$CACHE"
    echo "  failed or digest mismatch, trying the next source" >&2
  done
fi

if [ ! -f "$CACHE" ]; then
  echo "could not fetch a ${ASSET} matching ${EXPECTED}" >&2
  exit 1
fi

rm -rf "$STAGE" "$PKG"
# No sweep by name here either: the shelf is a drop box, and `rm -f ${SHELF}/${NAME}-*.zip` would take
# a hand-dropped newer version with it. ../build.sh resolves same-name duplicates by keeping the
# highest manifest version and moving the loser to .superseded/.
mkdir -p "${STAGE}/skill" "${STAGE}/${BIN_DEST%/*}" "${STAGE}/${DOC_DEST}"

# The archive is flat: ./lark-cli, ./LICENSE, ./README.md, ./CHANGELOG.md. Only the binary and the
# license it is distributed under land in the image.
tar -xzf "$CACHE" -C "$STAGE" "$NAME"
mv "${STAGE}/${NAME}" "${STAGE}/${BIN_DEST}"
tar -xzf "$CACHE" -C "${STAGE}/${DOC_DEST}" LICENSE
cp plugin.yaml "${STAGE}/plugin.yaml"
cp skill/SKILL.md "${STAGE}/skill/SKILL.md"
# The execute bit travels to the image through the zip's central directory, and admin refuses a
# payload with no mode recorded at all rather than landing a 0644 binary that fails as 126.
chmod 755 "${STAGE}/${BIN_DEST}"

(
  cd "$STAGE"
  # -X: no extra fields, so the same bytes pack to the same package digest every time.
  zip -X -q -r "$PKG" plugin.yaml skill payload
)

echo "${PKG} ($(wc -c < "$PKG" | tr -d ' ') bytes, linux/${ARCH})"
