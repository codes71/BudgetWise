#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["CairoSVG==2.9.1", "Pillow==12.3.0", "fonttools==4.68.0"]
# ///
"""Export approved Pocket Peek assets. Never writes Android resources or changes app styling.

From repository root:
  DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib uv run --script brand/render.py --target all
Targets: web (public logo/favicon/SVGs and typed React icons), store (Play PNG/SVG), all.
Canonical vectors are the root brand SVGs and brand/icons/*.svg. Approved input PNGs
in brand/source remain byte-identical copies of the owner-approved handoff.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

import cairosvg
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT / "brand"
PUBLIC = ROOT / "apps/web/public"
STORE = ROOT / "docs/play-store/graphics"
COMPONENT = ROOT / "apps/web/src/components/brand/pocket-peek-icons.tsx"
SVG_NS = "http://www.w3.org/2000/svg"


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)


def svg_png(source, destination, width, height):
    destination.parent.mkdir(parents=True, exist_ok=True)
    cairosvg.svg2png(url=str(source), write_to=str(destination), output_width=width, output_height=height)


def react_icons():
    icons = json.loads((BRAND / "icons.json").read_text())["icons"]
    node_sets = {}
    for icon in icons:
        svg = ET.parse(BRAND / icon["path"]).getroot()
        nodes = []
        for element in svg:
            tag = element.tag.removeprefix(f"{{{SVG_NS}}}")
            if tag == "title":
                continue
            if tag not in {"path", "circle", "rect"}:
                raise ValueError(f"Unsupported approved icon element: {tag}")
            nodes.append([tag, dict(element.attrib)])
        node_sets[icon["name"]] = nodes
    data = json.dumps(node_sets, indent=2)
    source = '''// Generated from approved brand/icons/*.svg by brand/render.py. Do not edit geometry here.
'use client';

import * as React from 'react';

export interface PocketPeekIconProps extends React.SVGProps<SVGSVGElement> {
  size?: number | string;
  absoluteStrokeWidth?: boolean;
}

type IconNode = readonly [string, Readonly<Record<string, string>>];
const nodes = ''' + data + ''' as const;

function createIcon(name: keyof typeof nodes) {
  const Icon = React.forwardRef<SVGSVGElement, PocketPeekIconProps>(
    ({ size = 24, color = 'currentColor', strokeWidth = 2, absoluteStrokeWidth = false, children, ...props }, ref) => (
      <svg
        ref={ref}
        xmlns="http://www.w3.org/2000/svg"
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke={color}
        strokeWidth={absoluteStrokeWidth ? Number(strokeWidth) * 24 / Number(size) : strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden={props['aria-label'] || props['aria-labelledby'] ? undefined : true}
        focusable="false"
        {...props}
      >
        {(nodes[name] as readonly IconNode[]).map(([tag, attributes], index) =>
          React.createElement(tag, { ...attributes, key: index }))}
        {children}
      </svg>
    ),
  );
  Icon.displayName = `PocketPeek${name.split('-').map((part) => part[0].toUpperCase() + part.slice(1)).join('')}`;
  return Icon;
}

'''
    for name in node_sets:
        export_name = "".join(word.title() for word in name.split("-"))
        source += f"export const {export_name} = createIcon('{name}');\n"
    write(COMPONENT, source)


def web():
    copy(BRAND / "source/store-icon-512.png", PUBLIC / "logo.png")
    # Only technical ICO encoding/resizing of the approved favicon PNG exports.
    favicon32 = Image.open(BRAND / "source/favicon-32.png").convert("RGBA")
    favicon64 = Image.open(BRAND / "source/favicon-64.png").convert("RGBA")
    favicon64.save(PUBLIC / "favicon.ico", format="ICO", sizes=[(16, 16), (32, 32), (64, 64)],
                   append_images=[favicon32.resize((16, 16), Image.Resampling.LANCZOS), favicon32])
    # Compatibility names now resolve to the approved Pocket Peek design.
    for old, new in [("logo-mark.svg", "store-icon.svg"), ("logo-mark-mono.svg", "symbol-forest.svg"),
                     ("logo-wordmark.svg", "logo-horizontal.svg")]:
        copy(BRAND / new, BRAND / old)
    for path in BRAND.glob("*.svg"):
        copy(path, PUBLIC / "brand" / path.name)
    for path in (BRAND / "icons").glob("*.svg"):
        copy(path, PUBLIC / "brand/icons" / path.name)
    for name in ["palette.json", "icons.json"]:
        copy(BRAND / name, PUBLIC / "brand" / name)
    copy(BRAND / "fonts/OFL.txt", PUBLIC / "brand/Nunito-OFL.txt")
    copy(BRAND / "source/favicon-32.png", PUBLIC / "brand/favicon-32.png")
    copy(BRAND / "source/favicon-64.png", PUBLIC / "brand/favicon-64.png")
    react_icons()


def outline(text, size):
    font = TTFont(BRAND / "fonts/Nunito-ExtraBold.ttf")
    glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
    scale = size / font["head"].unitsPerEm
    x, paths = 0, []
    for character in text:
        glyph = cmap[ord(character)]
        pen = SVGPathPen(glyphs)
        glyphs[glyph].draw(TransformPen(pen, (scale, 0, 0, -scale, x, 0)))
        if pen.getCommands():
            paths.append(f'<path d="{pen.getCommands()}"/>')
        x += font["hmtx"][glyph][0] * scale
    font.close()
    return "".join(paths)


def store():
    STORE.mkdir(parents=True, exist_ok=True)
    copy(BRAND / "source/store-icon-512.png", STORE / "store-icon-512.png")
    logo = ET.parse(BRAND / "logo-horizontal.svg").getroot()
    viewbox = [float(value) for value in logo.attrib["viewBox"].split()]
    scale = 824 / viewbox[2]
    logo_body = "".join(ET.tostring(child, encoding="unicode") for child in logo
                        if child.tag.removeprefix(f"{{{SVG_NS}}}") not in {"title", "metadata"})
    # Existing truthful product promise, with no screenshot, rating or financial claim.
    feature = ('<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">'
               '<title>Pocket Peek, See where your money goes.</title>'
               '<rect width="1024" height="500" fill="#F3FCF8"/>'
               f'<g transform="translate(100 95) scale({scale:.12f}) translate(2 2)">{logo_body}</g>'
               f'<g transform="translate(100 350)" fill="#143429">{outline("See where your money goes.", 48)}</g>'
               '</svg>\n')
    write(STORE / "feature-graphic-1024x500.svg", feature)
    svg_png(STORE / "feature-graphic-1024x500.svg", STORE / "feature-graphic-1024x500.png", 1024, 500)
    # Play accepts an opaque PNG. Drop alpha without changing the approved mint background.
    for filename in ["store-icon-512.png", "feature-graphic-1024x500.png"]:
        with Image.open(STORE / filename) as image:
            if image.mode != "RGB":
                image.convert("RGB").save(STORE / filename, optimize=True)


def manifest():
    roots = [PUBLIC / "brand", STORE]
    paths = [PUBLIC / "logo.png", PUBLIC / "favicon.ico", COMPONENT]
    for root in roots:
        if root.exists():
            paths.extend(path for path in root.rglob("*") if path.is_file() and path.suffix != ".md")
    entries = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
               for path in sorted(paths) if path.exists()}
    write(BRAND / "export-manifest.json", json.dumps(entries, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["web", "store", "all"], default="all")
    args = parser.parse_args()
    selection = json.loads((BRAND / "selection.json").read_text())
    if selection["concept"]["id"] != "01" or selection["brand"] != "Pocket Peek":
        raise SystemExit("Only approved Pocket Peek Folded Pocket 01 may be exported")
    if args.target in {"web", "all"}:
        web()
    if args.target in {"store", "all"}:
        store()
    manifest()
    print(f"Pocket Peek {args.target} assets exported. Android resources untouched.")


if __name__ == "__main__":
    main()
