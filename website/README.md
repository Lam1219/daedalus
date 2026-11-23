# Medicina website

A static landing page in English (`index.html`) and French (`fr/index.html`), with one stylesheet and the Manrope font copied from the Android app. It has no scripts, no cookies, no tracking and no forms. Contact is a plain email link.

The logo in the header is the Care bloom mark, inlined as SVG. `favicon.ico`, `favicon.svg` and `apple-touch-icon.png` are copies from `brand/medicina/` (regenerate them there, then copy them again).

Preview it locally:

```bash
python3 -m http.server 8765 --directory website
```

## Before it goes online

- The contact address is medicina.assistant@gmail.com, in the "Who is helping develop it" section (`#contact`).
- Register the business name "Medicina" in Ontario (or incorporate). The page names Medicina to the public, which Ontario's Business Names Act counts as carrying on business under that name.
- Every claim stays descriptive: what the app does, never that it treats or improves anything. Daedalus is planned as a Class I device (`docs/product/proposal.html` § 8, D6), and marketing wording affects how Health Canada classifies it.

The pages use root paths (`/style.css`, `/fonts/…`), so they need to be served from the root of a domain, not from a subfolder.
