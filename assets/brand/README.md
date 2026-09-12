# Bun Do logo

The approved refined salute, with its defined nose, calm eye, joined paws and tied belt. Use this same drawing at small sizes. The owner rejected the separate compact variant.

## Files

- `bun-do-evergreen.svg`: primary transparent vector, `#245B48`.
- `bun-do-paper.svg`: reverse vector, `#F8F9F4`, for dark or evergreen backgrounds.
- `png/`: transparent exports at 24, 48, 96, 192, 512 and 1024 pixels.
- `source/approved-raster.png`: the owner-approved visual reference, not an alternate logo.
- `source/provenance.json`: approval, generation and cleanup history.
- `index.html`: vector proof with the approved reference and small-size samples.

The vectors contain one filled compound path with transparent cutouts. No embedded raster, gradient, filter or partially opaque fill. The paper and evergreen files share exactly the same geometry.

## Rebuild

Requires Python 3, ImageMagick and Potrace. Run from the repository root:

```sh
python3 assets/brand/build.py
python3 assets/brand/check-vector.py
python3 assets/brand/check-flat-color.py assets/brand/png/bun-do-evergreen-1024.png
```

`build.py` traces the approved silhouette into smooth curves, then renders the PNGs from the SVG. It never generates a new character. The reference preserves the approved pose and prevents later tracing changes from silently redesigning the mark.

## Usage

Use evergreen on light backgrounds and paper on dark backgrounds. Do not stretch, add shading or remove the nose at small sizes. The SVG's nominal size is 48 pixels and its view box is 1024 units.

Launcher crop previews are composition studies, not Android adaptive-icon resources. Android integration still needs foreground/background layers and emulator or device mask checks.
