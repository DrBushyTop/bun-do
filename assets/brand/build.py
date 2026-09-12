#!/usr/bin/env python3
"""Trace the approved silhouette and export the two flat-color variants."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
COLORS = {"evergreen": "#245B48", "paper": "#F8F9F4"}
SIZES = (24, 48, 96, 192, 512, 1024)


def run(*args):
    subprocess.run(args, check=True)


with tempfile.TemporaryDirectory(prefix="bun-do-logo-") as directory:
    bitmap = str(Path(directory) / "silhouette.pbm")
    traced = str(Path(directory) / "traced.svg")
    run(
        "magick", str(ROOT / "source/approved-raster.png"),
        "-alpha", "extract", "-threshold", "50%", "-negate", bitmap,
    )
    run(
        "potrace", bitmap, "--svg", "--flat", "--opttolerance", "0.4",
        "--color", COLORS["evergreen"], "-o", traced,
    )
    tree = ET.parse(traced)
    group = tree.find(".//{http://www.w3.org/2000/svg}g")
    path = tree.find(".//{http://www.w3.org/2000/svg}path")
    transform = group.attrib["transform"]
    data = " ".join(path.attrib["d"].split())
    for variant, color in COLORS.items():
        svg = ROOT / f"bun-do-{variant}.svg"
        svg.write_text(
            '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" '
            'viewBox="0 0 1024 1024" role="img" aria-labelledby="title">\n'
            '  <title id="title">Bun Do rabbit salute</title>\n'
            '  <metadata>Vectorized from the owner-approved refined salute. '
            'See source/provenance.json for generation and approval history.</metadata>\n'
            f'  <path fill="{color}" fill-rule="nonzero" transform="{transform}" '
            f'd="{data}"/>\n'
            '</svg>\n'
        )
        for size in SIZES:
            output = ROOT / "png" / f"bun-do-{variant}-{size}.png"
            output.parent.mkdir(exist_ok=True)
            run(
                "magick", "-background", "none", "-density", "6144",
                str(svg), "-resize", f"{size}x{size}",
                "-channel", "RGB", "-fill", color, "-colorize", "100",
                "+channel", "-set", "impeccable:prompt",
                f"Rendered from bun-do-{variant}.svg by assets/brand/build.py; "
                "owner-approved Bun Do salute. See source/provenance.json.",
                str(output),
            )
print("Built two SVG variants and twelve transparent PNG exports.")
