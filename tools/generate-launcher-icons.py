#!/usr/bin/env python3
"""Convert the supplied V.ico to Android PNGs. Requires Pillow only to regenerate."""
from pathlib import Path

from PIL import Image


root = Path(__file__).resolve().parent.parent
res = root / "app/src/main/res"
with Image.open(root / "branding/V.ico") as icon:
    # Use the largest original frame for every density; never redraw the artwork.
    source = icon.ico.getimage(max(icon.ico.sizes(), key=lambda size: size[0] * size[1]))
    source = source.convert("RGBA")
    source.save(res / "drawable-nodpi/ic_launcher_art.png")
    for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)]:
        folder = res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        source.resize((size, size), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png")
