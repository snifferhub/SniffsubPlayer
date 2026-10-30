#!/usr/bin/env python3
"""Regenerate Android launcher resources from the opaque iOS icon masters.

Requires Python 3 and Pillow. Run from any working directory.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MASTERS = ROOT / 'iosApp/SniffSubPlayer/Assets.xcassets/AppIcon.appiconset'
RES = ROOT / 'app/src/main/res'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

for night, filename in [(False, 'AppIcon.png'), (True, 'dark_icon.png')]:
    icon = Image.open(MASTERS / filename).convert('RGB')
    # Adaptive icon layers are 108dp; the central 72dp is the visible viewport.
    # Extend edge pixels into the overscan region instead of enlarging the plane.
    layer = Image.new('RGB', (1536, 1536))
    layer.paste(icon, (256, 256))
    layer.paste(icon.crop((0, 0, 1024, 1)).resize((1024, 256)), (256, 0))
    layer.paste(icon.crop((0, 1023, 1024, 1024)).resize((1024, 256)), (256, 1280))
    layer.paste(layer.crop((256, 0, 257, 1536)).resize((256, 1536)), (0, 0))
    layer.paste(layer.crop((1279, 0, 1280, 1536)).resize((256, 1536)), (1280, 0))
    for density, scale in DENSITIES.items():
        folder = RES / f'mipmap-{"night-" if night else ""}{density}'
        folder.mkdir(parents=True, exist_ok=True)
        size = round(48 * scale)
        legacy = icon.resize((size, size), Image.Resampling.LANCZOS)
        legacy.save(folder / 'ic_launcher.png')
        circle = Image.new('L', (size * 4, size * 4))
        ImageDraw.Draw(circle).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        rounded = legacy.convert('RGBA')
        rounded.putalpha(circle.resize((size, size), Image.Resampling.LANCZOS))
        rounded.save(folder / 'ic_launcher_round.png')
        adaptive_size = round(108 * scale)
        layer.resize((adaptive_size, adaptive_size), Image.Resampling.LANCZOS).save(
            folder / 'ic_launcher_foreground.png'
        )
