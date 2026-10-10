# Monocraft — provenance and web embedding

Checked: 2026-10-10.

Author/upstream: [Idrees Hassan / IdreesInc, Monocraft](https://github.com/IdreesInc/Monocraft).

Pinned upstream commit: `e498bf70aeb25b4bdcff1e44d878fb2cb4f7c2a9`.

Sources:

- [Original TTF at pinned commit](https://github.com/IdreesInc/Monocraft/blob/e498bf70aeb25b4bdcff1e44d878fb2cb4f7c2a9/dist/Monocraft-ttf/Monocraft.ttf)
- [Original license at pinned commit](https://github.com/IdreesInc/Monocraft/blob/e498bf70aeb25b4bdcff1e44d878fb2cb4f7c2a9/LICENSE)
- [Upstream README](https://github.com/IdreesInc/Monocraft/blob/e498bf70aeb25b4bdcff1e44d878fb2cb4f7c2a9/README.md)
- [W3C WOFF 1.0 packaging specification](https://www.w3.org/TR/WOFF/)

License: **SIL Open Font License 1.1**, copyright (c) 2022 Idrees Hassan. Keep `LICENSE-Monocraft.txt` with redistributed font files. This is an independent fan font. The upstream states that the project includes no original Minecraft font files or game assets; do not present it as an official Mojang font or endorsement.

## Local files

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| Monocraft.ttf | 210192 | `262e31822e0a75be567b02320bfa092143d871e8a273e529d1c5ab0c04cd215f` |
| Monocraft.woff | 46996 | `ba89c9ccf96c5d6046e09c6c8bcd8333a3a35881aa11fb1493a257f96abfe1ba` |
| LICENSE-Monocraft.txt | 4377 | `f69c147003e052dbc9d96c40a9f73647e72766cfda95a597b94ed827fe25acb1` |

The TTF and license are exact downloads from the pinned upstream. The WOFF is a local lossless table wrapper with zlib compression; all 16 decoded WOFF tables were compared byte-for-byte to the original TTF. No outline, name, character map or font feature was edited, and no font was installed into the operating system. Metadata and licensing tables remain intact. The unmodified TTF is retained as a fallback and recovery source.

## Glyph and browser verification

Direct `cmap` inspection found 1475 mapped codepoints and confirmed all 66 Russian uppercase/lowercase letters, including `Ё/ё`, plus `ІіЇїЄєҐґЎў`. Details and hashes are in `Monocraft-verification.json`.

An isolated browser check loaded the WOFF through CSS `@font-face` in Edge 155.0.4283.45 with Playwright 1.62.1: one face loaded, `FontFace.status === 'loaded'`, `document.fonts.check` passed for Russian text, and no page errors occurred. This confirms the font file is accepted by that tested browser; it is not a complete cross-browser or UI accessibility claim.

## Embedding

This project serves public resources from the root. If deployment changes its asset base, use the corresponding public base URL.

```css
@font-face {
  font-family: 'Monocraft';
  src: url('/fonts/Monocraft.woff') format('woff'),
       url('/fonts/Monocraft.ttf') format('truetype');
  font-style: normal;
  font-weight: 400;
  font-display: swap;
}
```

Use one real 400 face; do not request a fictitious 100–900 variable range. Prefer `font-synthesis: none` for pixel display roles. Keep `letter-spacing: 0` and avoid scaling a text container with CSS transforms.

For this outline, `unitsPerEm = 1080`; the inspected `M` glyph follows a 120-unit pixel lattice. Sizes divisible by 9 (18, 27, 36, 45 CSS px) align its basic lattice to integer CSS pixels at 1× density. Other glyphs, DPR, baseline placement and scaling may still alter rasterization; inspect the actual result.

Suggested roles: Monocraft for brand, headings, KPI numbers and short game actions; the existing readable UI sans-serif for paragraphs, data tables, forms and long labels. Use appropriate line-height around 1.35–1.45 and verify Cyrillic accents/descenders; never clip text into a fixed short height. This is implementation guidance, not a license condition.
