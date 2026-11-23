"""Build the Medicina brand kit (concept C, Care bloom): SVGs and PNGs."""
import sys
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from PIL import Image, ImageDraw

repo = Path(sys.argv[1])
out = repo / "brand" / "medicina"
png = out / "png"
png.mkdir(parents=True, exist_ok=True)

SAGE_DEEP, SAGE, SAGE_LIGHT = "#34452F", "#4E6447", "#C9D8C1"
WHITE, ACCENT, INK, GROUND = "#FFFFFF", "#E39A62", "#1E2620", "#F5F6F1"

def bloom(petal_a, petal_b, centre, dot_r=7.0):
    """Mark in 100-unit space: four petals in a soft cross, warm centre."""
    return [(50, 27, 15, petal_a), (73, 50, 15, petal_b), (50, 73, 15, petal_a),
            (27, 50, 15, petal_b), (50, 50, dot_r, centre)]

def icon_bloom(petal_a, petal_b, centre):
    """The mark scaled for a full-bleed square icon (about 53% of the side)."""
    return [(50, 34, 10.5, petal_a), (66, 50, 10.5, petal_b), (50, 66, 10.5, petal_a),
            (34, 50, 10.5, petal_b), (50, 50, 5, centre)]

def circles_svg(circles, indent="  "):
    return "\n".join(f'{indent}<circle cx="{x:g}" cy="{y:g}" r="{r:g}" fill="{c}"/>' for x, y, r, c in circles)

def write(name, body):
    (out / name).write_text(body)

# --- Wordmark as outlines (no font needed to display it) ---
font = TTFont(repo / "website" / "fonts" / "manrope_bold.ttf")
upm = font["head"].unitsPerEm
cap = font["OS/2"].sCapHeight
gs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]

def wordmark_path(text, size, x0, baseline, tracking=-0.03):
    s = size / upm
    pen = SVGPathPen(gs)
    x = 0.0
    for ch in text:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (s, 0, 0, -s, x0 + x * s, baseline)))
        x += hmtx[g][0] + tracking * upm
    width = (x - tracking * upm) * s
    return pen.getCommands(), width

def lockup(name, circles, text_fill):
    size, gap = 78, 20
    baseline = 50 + cap * size / upm / 2
    d, w = wordmark_path("medicina", size, 88 + gap, baseline)
    total = 88 + gap + w + 12
    write(name, f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="12 12 {total - 12:.1f} 76" role="img" aria-label="Medicina">
{circles_svg(circles)}
  <path fill="{text_fill}" d="{d}"/>
</svg>
''')

# --- SVGs ---
mark_head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="12 12 76 76" role="img" aria-label="Medicina">'
write("medicina-mark.svg", f"{mark_head}\n{circles_svg(bloom(SAGE_DEEP, SAGE, ACCENT))}\n</svg>\n")
write("medicina-mark-reversed.svg", f"{mark_head}\n{circles_svg(bloom(WHITE, SAGE_LIGHT, ACCENT))}\n</svg>\n")
write("medicina-mark-mono.svg", f"{mark_head}\n{circles_svg(bloom('currentColor', 'currentColor', 'currentColor'))}\n</svg>\n")
write("medicina-mark-small.svg", f"{mark_head}\n{circles_svg(bloom(SAGE_DEEP, SAGE, ACCENT, dot_r=9.5))}\n</svg>\n")
lockup("medicina-lockup.svg", bloom(SAGE_DEEP, SAGE, ACCENT), INK)
lockup("medicina-lockup-reversed.svg", bloom(WHITE, SAGE_LIGHT, ACCENT), WHITE)
write("medicina-app-icon.svg", f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" role="img" aria-label="Medicina">
  <rect width="100" height="100" fill="{SAGE_DEEP}"/>
{circles_svg(icon_bloom(WHITE, SAGE_LIGHT, ACCENT))}
</svg>
''')

# --- PNGs (supersampled for smooth edges) ---
def render(circles, px, bg=None, view=(0, 0, 100), shape="square"):
    ss = 8 if px <= 64 else 4
    big = px * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    if bg:
        if shape == "circle":
            dr.ellipse([0, 0, big - 1, big - 1], fill=bg)
        else:
            dr.rectangle([0, 0, big, big], fill=bg)
    ox, oy, span = view
    k = big / span
    for x, y, r, c in circles:
        dr.ellipse([(x - r - ox) * k, (y - r - oy) * k, (x + r - ox) * k, (y + r - oy) * k], fill=c)
    return img.resize((px, px), Image.LANCZOS)

icon = icon_bloom(WHITE, SAGE_LIGHT, ACCENT)
for px in (1024, 512, 180):
    render(icon, px, SAGE_DEEP).convert("RGB").save(png / f"app-icon-{px}.png")
render(icon, 512, SAGE_DEEP, shape="circle").save(png / "watch-icon-512.png")
# Android adaptive icon: foreground on transparent 432 px canvas, background is SAGE_DEEP.
render(icon, 432).save(png / "android-adaptive-foreground-432.png")
tight = (12, 12, 76)
render(bloom(SAGE_DEEP, SAGE, ACCENT), 512, view=tight).save(png / "mark-512.png")
render(bloom(WHITE, SAGE_LIGHT, ACCENT), 512, view=tight).save(png / "mark-reversed-512.png")
fav = [render(bloom(SAGE_DEEP, SAGE, ACCENT, dot_r=9.5), s, view=tight) for s in (16, 32, 48)]
fav[1].save(png / "favicon-32.png")
fav[2].save(out / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)], append_images=fav[:2])
print("wrote", sorted(p.relative_to(repo).as_posix() for p in out.rglob("*") if p.is_file()))
