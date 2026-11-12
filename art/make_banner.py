"""Builds the Android TV launcher banner (320x180 at xhdpi) from art/splash-source.webp.

The TV launcher shows a banner with the app's name instead of a round icon. The name is drawn with
a bold system font (Arial Bold on Windows; pass another .ttf as the first argument elsewhere).

Run from the project root: python art/make_banner.py [font.ttf]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SOURCE = Path("art/splash-source.webp")
OUT = Path("app/src/main/res/drawable-xhdpi/tv_banner.png")
RING_BOX = (20, 21, 1224, 1223)  # the ring in the source, as in make_icon.py
SIZE = (320, 180)
BACKGROUND = (16, 16, 18)  # Palette.bgBase
RING = 132
FONT = sys.argv[1] if len(sys.argv) > 1 else "C:/Windows/Fonts/arialbd.ttf"
AA = 4


def ring_cutout(size: int) -> Image.Image:
    art = Image.open(SOURCE).convert("RGB").crop(RING_BOX).resize((size, size), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size * AA, size * AA), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * AA - 1, size * AA - 1), fill=255)
    art.putalpha(mask.resize((size, size), Image.LANCZOS))
    return art


def main() -> None:
    banner = Image.new("RGBA", SIZE, BACKGROUND + (255,))
    top = (SIZE[1] - RING) // 2
    banner.alpha_composite(ring_cutout(RING), (18, top))
    draw = ImageDraw.Draw(banner)
    font = ImageFont.truetype(FONT, 30)
    text = "ByeByeRKN"
    left = 18 + RING + 14
    box = draw.textbbox((0, 0), text, font=font)
    # Shrink to fit the space right of the ring.
    while box[2] - box[0] > SIZE[0] - left - 10 and font.size > 12:
        font = ImageFont.truetype(FONT, font.size - 1)
        box = draw.textbbox((0, 0), text, font=font)
    y = (SIZE[1] - (box[3] - box[1])) // 2 - box[1]
    draw.text((left, y), text, font=font, fill=(255, 255, 255, 255))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    banner.convert("RGB").save(OUT, optimize=True)
    print(OUT, banner.size, OUT.stat().st_size)


if __name__ == "__main__":
    main()
