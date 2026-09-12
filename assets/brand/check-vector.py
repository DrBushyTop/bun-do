#!/usr/bin/env python3
"""Validate vector geometry, exports and fidelity to the approved silhouette."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
NS = "{http://www.w3.org/2000/svg}"
paths = []
for variant, color in (("evergreen", "#245B48"), ("paper", "#F8F9F4")):
    root = ET.parse(ROOT / f"bun-do-{variant}.svg").getroot()
    assert root.attrib["viewBox"] == "0 0 1024 1024"
    assert {node.tag for node in root} == {
        NS + "title", NS + "metadata", NS + "path"
    }, "Only a title, provenance and a filled path belong in the SVG"
    path, = root.findall(NS + "path")
    assert path.attrib["fill"] == color
    assert "opacity" not in path.attrib
    paths.append((path.attrib["d"], path.attrib["transform"]))
    for size in (24, 48, 96, 192, 512, 1024):
        output = ROOT / "png" / f"bun-do-{variant}-{size}.png"
        actual = subprocess.check_output(
            ["magick", "identify", "-format", "%wx%h", str(output)], text=True
        )
        assert actual == f"{size}x{size}", (output, actual)
assert paths[0] == paths[1], "Color variants must use identical geometry"


def mask(path):
    return subprocess.check_output([
        "magick", str(path), "-alpha", "extract",
        "-threshold", "50%", "-depth", "8", "gray:-",
    ])


reference = mask(ROOT / "source/approved-raster.png")
vector = mask(ROOT / "png/bun-do-evergreen-1024.png")
intersection = sum(a > 0 and b > 0 for a, b in zip(reference, vector))
union = sum(a > 0 or b > 0 for a, b in zip(reference, vector))
iou = intersection / union
assert iou >= .99, f"Silhouette drift exceeds 1%: IoU {iou:.4%}"
print(f"PASS: two identical vector geometries; 12 export sizes; silhouette IoU {iou:.4%}")
