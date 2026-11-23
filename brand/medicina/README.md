# Medicina logo: Care bloom

Four round petals form a soft health cross, with a warm dot at the centre for the person. Chosen by the owner on 2026-11-23 from three directions.

## Files

| File | Use |
|---|---|
| `medicina-lockup.svg` | Mark and name, on light backgrounds |
| `medicina-lockup-reversed.svg` | Mark and name, on sage or dark backgrounds |
| `medicina-mark.svg` | The mark alone, on light backgrounds |
| `medicina-mark-reversed.svg` | The mark alone, on dark backgrounds |
| `medicina-mark-mono.svg` | One colour (`currentColor`), for print, stamps and embossing |
| `medicina-mark-small.svg` | For 24 px and below: the centre dot is larger so it doesn't disappear |
| `medicina-app-icon.svg` | Square, full-bleed icon (the platforms round the corners) |
| `favicon.ico`, `png/favicon-32.png` | Website tab icon (16, 32, 48 px) |
| `png/app-icon-1024.png` | App Store and Play Store icon |
| `png/app-icon-512.png`, `png/app-icon-180.png` | Smaller icon sizes (180 = Apple touch icon) |
| `png/watch-icon-512.png` | Round icon for watch faces |
| `png/android-adaptive-foreground-432.png` | Android adaptive icon foreground; set the background to `#34452F` |
| `png/mark-512.png`, `png/mark-reversed-512.png` | Transparent PNGs for slides and documents |

The name is drawn as outlines, so the SVGs look the same without the font installed.

## Colours

| Name | Hex | Role |
|---|---|---|
| Sage deep | `#34452F` | Top and bottom petals, icon background |
| Sage | `#4E6447` | Side petals |
| Sage light | `#C9D8C1` | Side petals on dark backgrounds |
| Apricot | `#E39A62` | Centre dot only |
| Ink | `#1E2620` | The name |
| Ground | `#F5F6F1` | Page background |

These are the website's own colours (`website/style.css`), plus apricot.

## Type

The name is Manrope Bold in lowercase, tracked −3%. Manrope is in `website/fonts/`.

## Rules

- **Clear space:** leave at least one petal's width on every side.
- **Smallest size:** 16 px for the mark alone, and 120 px wide for the lockup.
- **Don't:** recolour the petals one by one, use apricot anywhere except the centre, rotate the mark or add a shadow.

## Rebuilding

The files were generated from geometry, with petals of radius 15 at 23 units from the centre and a centre dot of radius 7 (9.5 in the small version), on a 100-unit grid. To change the mark, edit the numbers in `scripts/make_medicina_brand.py`, then run `python3 scripts/make_medicina_brand.py .` from the repository root (it needs Pillow and fontTools). Don't edit the PNGs by hand.
