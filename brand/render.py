#!/usr/bin/env python3
"""Regenerate every BudgetWise brand raster and Android icon vector from the SVG sources in brand/.

Requirements (not project dependencies; use a throwaway virtualenv):
    python 3.10+, cairosvg >= 2.7, Pillow >= 10.1
    macOS: cairo from Homebrew; if cairocffi cannot find it, run with
    DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib

Usage, from the repository root:
    uv venv /tmp/brand-venv && VIRTUAL_ENV=/tmp/brand-venv uv pip install cairosvg Pillow
    DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib /tmp/brand-venv/bin/python brand/render.py

Sources (edit these, then rerun):
    brand/logo-mark.svg                 full-color tile, 72 x 72
    brand/logo-wordmark.svg             tile plus "BudgetWise" lockup
    brand/icons/app-foreground.svg      adaptive icon foreground for :app, 108 x 108 dp
    brand/icons/voicelab-foreground.svg adaptive icon foreground for :asr-demo, 108 x 108 dp
    brand/concepts/*.svg                explored concepts, 108 x 108 dp, full bleed

Outputs:
    public/logo.png, public/favicon.ico
    android/{app,asr-demo}/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml
    android/{app,asr-demo}/src/main/res/values/ic_launcher_background.xml
    brand/concepts/<name>-512.png, <name>-48.png, contact-sheet.png
    brand/previews/launcher-icons.png, brand/previews/web-header.png
"""
import io
import math
import os
import re
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRAND = os.path.join(ROOT, "brand")
ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Icon layer source -> Android module that ships it.
ICONS = {
    "app": os.path.join(BRAND, "icons", "app-foreground.svg"),
    "asr-demo": os.path.join(BRAND, "icons", "voicelab-foreground.svg"),
}

CONCEPTS = [
    ("coin-stack", "Coin stack",
     "Three coins balanced like a cairn: savings built up with care. At 24 px it reads as a burger or a menu icon."),
    ("growth-leaf", "Growth leaf",
     "A split leaf for calm, steady growth. It says eco or wellness long before it says money."),
    ("allocation-ring", "Allocation ring",
     "A budget donut where every kyat has a slice. Clear but generic, and close to a loading spinner."),
    ("kept-coin", "Kept coin b",
     "A b whose counter holds a coin. Ring plus dot can read as the Myanmar digit zero, a poor sign for a budget."),
    ("budget-b", "Budget b (chosen)",
     "A b whose bowl is a budget ring with one quarter set aside. Reads at 24 px and in one color."),
]

MINT, INK, DARK_BG, FOREST = "#F3FCF8", "#030712", "#17191C", "#143429"


# ---------------------------------------------------------------- rendering helpers

def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def render(svg_text, width, height=None):
    data = cairosvg.svg2png(bytestring=svg_text.encode("utf-8"),
                            output_width=width, output_height=height or width)
    return Image.open(io.BytesIO(data)).convert("RGBA")


def squircle_mask(px, n=5.0, steps=720):
    pts = []
    h = px / 2
    for i in range(steps):
        t = 2 * math.pi * i / steps
        c, s = math.cos(t), math.sin(t)
        pts.append((h + h * math.copysign(abs(c) ** (2 / n), c),
                    h + h * math.copysign(abs(s) ** (2 / n), s)))
    return pts


def mask_image(px, shape, ss=4):
    big = Image.new("L", (px * ss, px * ss), 0)
    d = ImageDraw.Draw(big)
    if shape == "circle":
        d.ellipse((0, 0, px * ss - 1, px * ss - 1), fill=255)
    elif shape == "rounded":
        d.rounded_rectangle((0, 0, px * ss - 1, px * ss - 1), radius=px * ss * 0.2, fill=255)
    else:
        d.polygon([(x * ss, y * ss) for x, y in squircle_mask(px)], fill=255)
    return big.resize((px, px), Image.LANCZOS)


def launcher(svg108, px, shape="squircle"):
    """Render a 108 dp full-bleed icon the way a launcher shows it: the central 72 dp, masked."""
    full = round(px * 108 / 72)
    img = render(svg108, full)
    off = (full - px) // 2
    img = img.crop((off, off, off + px, off + px))
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask_image(px, shape))
    return out


def font(size):
    for path in ("/System/Library/Fonts/Supplemental/Arial.ttf",
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size=size)


# ---------------------------------------------------------------- Android vectors

def svg_paths(svg_text):
    """(d, fill) pairs from a flat SVG made only of <path d fill> elements."""
    root = ET.fromstring(svg_text)
    out = []
    for el in root.iter():
        if el.tag.endswith("path"):
            out.append((el.get("d"), el.get("fill")))
    return out


def svg_background(svg_text):
    m = re.search(r'data-background="(#[0-9A-Fa-f]{6})"', svg_text)
    if not m:
        raise SystemExit("icon source is missing data-background")
    return m.group(1).upper()


def vector_xml(paths, source, mono):
    rows = []
    for d, fill in paths:
        color = "#FF000000" if mono else "#FF" + fill.lstrip("#").upper()
        rows.append(f'    <path\n        android:fillColor="{color}"\n        android:pathData="{d}" />')
    kind = "monochrome (themed icon) layer" if mono else "foreground layer"
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- Adaptive icon {kind}. Generated by brand/render.py from brand/{source}; do not edit by hand. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n'
            + "\n".join(rows) + "\n</vector>\n")


def write_android_icons():
    for module, src in ICONS.items():
        text = read(src)
        paths = svg_paths(text)
        res = os.path.join(ROOT, "android", module, "src", "main", "res")
        rel = os.path.relpath(src, BRAND)
        write(os.path.join(res, "drawable", "ic_launcher_foreground.xml"), vector_xml(paths, rel, mono=False))
        write(os.path.join(res, "drawable", "ic_launcher_monochrome.xml"), vector_xml(paths, rel, mono=True))
        write(os.path.join(res, "values", "ic_launcher_background.xml"),
              '<?xml version="1.0" encoding="utf-8"?>\n'
              f"<!-- Generated by brand/render.py from brand/{rel}; do not edit by hand. -->\n"
              "<resources>\n"
              f'    <color name="ic_launcher_background">{svg_background(text)}</color>\n'
              "</resources>\n")


def vector_to_svg(res_dir, layer, background=True, mono_color=None):
    """Turn the generated VectorDrawable back into SVG, so previews show exactly what ships."""
    tree = ET.parse(os.path.join(res_dir, "drawable", f"ic_launcher_{layer}.xml"))
    bg = ET.parse(os.path.join(res_dir, "values", "ic_launcher_background.xml")).getroot()[0].text
    body = [f'<rect width="108" height="108" fill="{bg}"/>'] if background else []
    for p in tree.getroot():
        color = p.get(f"{{{ANDROID_NS}}}fillColor")
        fill = mono_color or "#" + color[-6:]
        body.append(f'<path d="{p.get(f"{{{ANDROID_NS}}}pathData")}" fill="{fill}"/>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">' + "".join(body) + "</svg>")


# ---------------------------------------------------------------- web

def favicon_tile(tile_svg, inset=7.0):
    """The tile cropped tighter (58 of 72 units) so the b stays legible at 16 px."""
    ET.register_namespace("", "http://www.w3.org/2000/svg")
    root = ET.fromstring(tile_svg)
    size = 72 - 2 * inset
    root.set("viewBox", f"{inset:g} {inset:g} {size:g} {size:g}")
    rect = next(el for el in root.iter() if el.tag.endswith("rect"))
    rect.set("x", f"{inset:g}")
    rect.set("y", f"{inset:g}")
    rect.set("width", f"{size:g}")
    rect.set("height", f"{size:g}")
    rect.set("rx", f"{size * 0.2:g}")
    return ET.tostring(root, encoding="unicode")


def write_web():
    tile = read(os.path.join(BRAND, "logo-mark.svg"))
    render(tile, 512).save(os.path.join(ROOT, "public", "logo.png"), optimize=True)
    sizes = [16, 32, 48]
    small = favicon_tile(tile)
    imgs = [render(small, s) for s in sizes]
    imgs[-1].save(os.path.join(ROOT, "public", "favicon.ico"), format="ICO",
                  sizes=[(s, s) for s in sizes], append_images=imgs[:-1])


# ---------------------------------------------------------------- previews

def contact_sheet():
    label_w, big, pad, row_h = 420, 512, 40, 512 + 80
    sheet_w = label_w + big + pad * 2 + 760
    sheet = Image.new("RGBA", (sheet_w, 90 + row_h * len(CONCEPTS)), "#FFFFFF")
    d = ImageDraw.Draw(sheet)
    d.text((pad, 28), "BudgetWise mark concepts (launcher previews crop to the visible 72 dp and mask)",
           font=font(30), fill=INK)
    for i, (name, title, note) in enumerate(CONCEPTS):
        svg = read(os.path.join(BRAND, "concepts", f"{name}.svg"))
        y = 90 + i * row_h
        chosen = name == "budget-b"
        if chosen:
            d.rectangle((8, y - 12, sheet_w - 8, y + row_h - 28), outline="#6CB299", width=6)
        d.text((pad, y + 10), title, font=font(34), fill=INK)
        words, line, ly = note.split(), "", y + 64
        for w in words:
            if d.textlength(line + " " + w, font=font(22)) > label_w - pad - 10:
                d.text((pad, ly), line.strip(), font=font(22), fill="#374151")
                line, ly = "", ly + 30
            line += " " + w
        d.text((pad, ly), line.strip(), font=font(22), fill="#374151")

        i512 = launcher(svg, 512)
        i512.save(os.path.join(BRAND, "concepts", f"{name}-512.png"))
        i48 = launcher(svg, 48)
        i48.save(os.path.join(BRAND, "concepts", f"{name}-48.png"))
        sheet.alpha_composite(i512, (label_w, y))

        x0 = label_w + big + pad * 2
        small = [
            (i48, 48),
            (launcher(svg, 24), 24),
            (launcher(svg, 24).resize((96, 96), Image.NEAREST), 96),
            (launcher(svg, 48, "circle"), 48),
        ]
        themed_src = re.sub(r'fill="#[0-9A-Fa-f]{6}"', 'fill="#0E3B2E"', svg).replace(
            f'<rect id="bg" width="108" height="108" fill="#0E3B2E"/>',
            '<rect id="bg" width="108" height="108" fill="#CDEBDD"/>')
        small.append((launcher(themed_src, 48, "circle"), 48))
        x = x0
        for img, w in small:
            sheet.alpha_composite(img, (x, y + 40 + (96 - w) // 2))
            x += w + 40
        for j, bgc in enumerate(("#F3FCF8", DARK_BG)):
            bx, by = x0 + j * 200, y + 220
            d.rounded_rectangle((bx, by, bx + 170, by + 90), radius=10, fill=bgc, outline="#D1D5DB")
            sheet.alpha_composite(launcher(svg, 40, "rounded"), (bx + 65, by + 25))
        d.text((x0, y + 330), "48, 24, 24 at 4x, round 48, themed 48; tile at 40 on light and dark",
               font=font(20), fill="#6B7280")
    sheet.convert("RGB").save(os.path.join(BRAND, "concepts", "contact-sheet.png"), optimize=True)


def launcher_preview():
    out = os.path.join(BRAND, "previews")
    os.makedirs(out, exist_ok=True)
    rows = []
    for module, label in (("app", "BudgetWise"), ("asr-demo", "BudgetWise Voice Lab")):
        res = os.path.join(ROOT, "android", module, "src", "main", "res")
        full = vector_to_svg(res, "foreground")
        mono = vector_to_svg(res, "monochrome", background=False, mono_color="#0E3B2E")
        themed = mono.replace('viewBox="0 0 108 108">', 'viewBox="0 0 108 108"><rect width="108" height="108" fill="#CDEBDD"/>')
        rows.append((label, full, themed))
    W, H = 1500, 720
    img = Image.new("RGBA", (W, H), "#E8EDEA")
    d = ImageDraw.Draw(img)
    d.text((40, 24), "Rendered from the generated VectorDrawable XML: squircle, circle, rounded square, 48 px, themed",
           font=font(26), fill=INK)
    for r, (label, full, themed) in enumerate(rows):
        y = 90 + r * 310
        d.text((40, y), label, font=font(28), fill=INK)
        x = 40
        for shape in ("squircle", "circle", "rounded"):
            img.alpha_composite(launcher(full, 192, shape), (x, y + 50))
            x += 232
        img.alpha_composite(launcher(full, 48), (x, y + 122))
        x += 88
        img.alpha_composite(launcher(themed, 192, "circle"), (x, y + 50))
        x += 232
        img.alpha_composite(launcher(themed, 48, "circle"), (x, y + 122))
    img.convert("RGB").save(os.path.join(out, "launcher-icons.png"), optimize=True)

    wm = read(os.path.join(BRAND, "logo-wordmark.svg"))
    vb = [float(v) for v in re.search(r'viewBox="([^"]+)"', wm).group(1).split()]
    ww = round(vb[2] / vb[3] * 72)
    web = Image.new("RGBA", (ww + 120, 2 * 132), "#FFFFFF")
    for j, (bg, text) in enumerate(((MINT, INK), (DARK_BG, "#F9FAFB"))):
        ImageDraw.Draw(web).rectangle((0, j * 132, ww + 120, j * 132 + 132), fill=bg)
        web.alpha_composite(render(wm.replace(f'fill="{INK}"', f'fill="{text}"'), ww, 72), (60, j * 132 + 30))
    web.convert("RGB").save(os.path.join(out, "web-header.png"), optimize=True)


def main():
    write_android_icons()
    write_web()
    contact_sheet()
    launcher_preview()
    print("brand assets regenerated")


if __name__ == "__main__":
    main()
