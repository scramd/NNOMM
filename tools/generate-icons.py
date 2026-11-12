# /// script
# dependencies = ["pillow", "cairosvg"]
# ///
"""Run with uv run tools/generate-icons.py; all formats come from icon.svg."""
from pathlib import Path

import cairosvg
from PIL import Image

root = Path(__file__).resolve().parent.parent
icons = root / "composeApp/icons"
cairosvg.svg2png(url=str(icons / "icon.svg"), write_to=str(icons / "icon.png"))
with Image.open(icons / "icon.png") as image:
    image.save(icons / "icon.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    image.save(icons / "icon.icns")
    image.resize((256, 256)).save(
        root / "composeApp/src/jvmMain/composeResources/drawable/iconpng.png"
    )
