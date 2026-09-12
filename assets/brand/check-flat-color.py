#!/usr/bin/env python3
"""Check logo ink and deep-interior opacity, excluding antialiased edges."""
import subprocess
import sys
from pathlib import Path

failed = False
for argument in sys.argv[1:]:
    path = Path(argument)
    rgba = subprocess.check_output(
        ["magick", str(path), "-depth", "8", "rgba:-"]
    )
    interior = subprocess.check_output(
        [
            "magick", str(path), "-alpha", "extract", "-threshold", "50%",
            # Five pixels exclude the antialiased tips of narrow vector cuts.
            "-morphology", "Erode", "Disk:5", "-depth", "8", "gray:-",
        ]
    )
    colors = {
        tuple(rgba[index:index + 3])
        for index in range(0, len(rgba), 4)
        if rgba[index + 3] >= 128
    }
    alphas = {
        rgba[index * 4 + 3]
        for index, value in enumerate(interior)
        if value == 255
    }
    passed = colors == {(36, 91, 72)} and alphas == {255}
    failed |= not passed
    print(
        f"{'PASS' if passed else 'FAIL'} {path.name}: "
        f"{len(colors)} visible ink colors; "
        f"deep-interior alpha {min(alphas, default=0)}..{max(alphas, default=0)}"
    )
sys.exit(1 if failed else 0)
