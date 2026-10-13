#!/usr/bin/env python3
"""Replace the solid #0D67F4 tile in the app-icon SVGs with a vertical brand gradient
(top #0D67F4 → bottom #007fff), then re-render the dependent PNGs.

Run this AFTER export_variants.py so the bg-tile SVGs are already generated.
If you re-export the kit, run this again to keep the gradient.

Targets:
  - harnax-icon-app-icon.svg                (kit PNGs 32/64/128/256/512)
  - apple-touch-icon, icon-192, icon-512, maskable-512  (web set)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, "dist")

GRAD_ID = "harnaxBgGrad"
# Diagonal (top-left → bottom-right) brand gradient
START_COLOR = "#023cb2"
END_COLOR = "#0c6cfb"


def gradient_defs():
    return (
        f'<defs><linearGradient id="{GRAD_ID}" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{START_COLOR}"/>'
        f'<stop offset="1" stop-color="{END_COLOR}"/>'
        f'</linearGradient></defs>'
    )


_GRAD_RE = re.compile(
    r'<defs>\s*(?:<linearGradient\b[^>]*id="' + re.escape(GRAD_ID) + r'"[^>]*>.*?</linearGradient>)?\s*</defs>',
    re.S,
)


def apply_gradient_to_svg(path):
    with open(path, encoding="utf-8") as fh:
        raw = fh.read()
    new = _GRAD_RE.sub("", raw)  # idempotent: drop any prior gradient with this id
    # Replace the bg rect fill — handles both fresh (#0D67F4) and already-gradienced (url(#harnaxBgGrad))
    new, n = re.subn(
        r'(<rect[^>]*fill=")(?:#0D67F4|url\(#' + re.escape(GRAD_ID) + r'\))(")',
        r'\1url(#' + GRAD_ID + r')\2',
        new,
        count=1,
    )
    if n != 1:
        print(f"  ! no #0D67F4 / url(#harnaxBgGrad) rect found in {os.path.basename(path)}")
        return False
    new, n = re.subn(r'(<svg\b[^>]*>)', r'\1' + gradient_defs(), new, count=1)
    if n != 1:
        print(f"  ! could not inject <defs> in {os.path.basename(path)}")
        return False
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new)
    return True


def main():
    sys.path.insert(0, os.path.join(os.path.expanduser("~"), ".qoder", "skills", "logo-design", "scripts"))
    import render_png  # noqa: E402

    if not os.path.isdir(DIST):
        print("run export_variants.py first to create", DIST)
        return 1

    # 1) Apply gradient to app-icon.svg
    targets_svg = [
        os.path.join(DIST, "harnax-icon-app-icon.svg"),
        os.path.join(DIST, "favicon.svg"),  # backup copy of tile if present
    ]
    for p in targets_svg:
        if os.path.exists(p) and p.endswith("-app-icon.svg"):
            print("gradient →", os.path.basename(p))
            if apply_gradient_to_svg(p):
                print(f"  ok {os.path.basename(p)}")

    # 2) Re-render the PNGs that carry the blue tile background
    app_svg = os.path.join(DIST, "harnax-icon-app-icon.svg")
    jobs = []
    if os.path.exists(app_svg):
        # Kit PNGs for the app-icon variant
        for size in (32, 64, 128, 256, 512):
            out = os.path.join(DIST, f"harnax-icon-app-icon-{size}.png")
            jobs.append((app_svg, out, size, size))
        # Web icons that use the tile
        jobs += [
            (app_svg, os.path.join(DIST, "apple-touch-icon.png"), 180, 180),
            (app_svg, os.path.join(DIST, "icon-192.png"), 192, 192),
            (app_svg, os.path.join(DIST, "icon-512.png"), 512, 512),
            (app_svg, os.path.join(DIST, "maskable-512.png"), 512, 512),
        ]
    ok = 0
    for svg, png, w, h in jobs:
        if render_png.render(svg, png, w, h):
            ok += 1
            print(f"  wrote {os.path.basename(png)}  {w}×{h}")
    print(f"\n{ok}/{len(jobs)} PNGs re-rendered with gradient")
    return 0 if ok == len(jobs) else 2


if __name__ == "__main__":
    sys.exit(main())
