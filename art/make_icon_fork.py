"""Fork icon: a cyan ring with a gap and a white bird flying out of it (art/icon-fork-source.webp).

Builds from that one picture:
  - the adaptive launcher foreground (mipmap-*/ic_launcher_foreground.webp),
  - the splash icon (drawable-nodpi/splash_icon.webp),
  - the Android TV banner (drawable-xhdpi/tv_banner.png),
  - the README picture (art/readme.webp),
  - monochrome vectors: drawable/ic_tile.xml (notification and quick-settings tile) and
    drawable/ic_launcher_monochrome.xml (themed icons of Android 13+), traced from
    art/icon-fork-mono.webp.

Run from the project root: python art/make_icon_fork.py [font.ttf]
"""
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = Path("app/src/main/res")
SOURCE = Path("art/icon-fork-source.webp")
MONO_SOURCE = Path("art/icon-fork-mono.webp")  # the same icon in one colour on a transparent background
# The ring in the source, measured from its pixels: centre and outer radius.
CX, CY, R_OUT = 627.0, 583.0, 408.0
BACKGROUND = (1, 8, 17)  # #010811, the picture's own dark navy
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
CANVAS_DP, RING_DP = 108, 62  # launchers show the central 72dp; the ring sits inside it
ANTIALIAS = 4


def save(image: Image.Image, path: Path, **kw) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, **kw)
    print(path, image.size, path.stat().st_size)


def launcher_layer(art: Image.Image, scale: float) -> Image.Image:
    canvas = round(CANVAS_DP * scale)
    factor = RING_DP * scale / (2 * R_OUT)
    resized = art.resize((round(art.width * factor), round(art.height * factor)), Image.LANCZOS)
    layer = Image.new("RGB", (canvas, canvas), BACKGROUND)
    # Soft edges, so the picture melts into the flat colour around it.
    mask = Image.new("L", resized.size, 0)
    inset = max(2, round(resized.width * 0.06))
    ImageDraw.Draw(mask).rectangle((inset, inset, resized.width - inset, resized.height - inset), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(inset / 2))
    layer.paste(resized, (round(canvas / 2 - CX * factor), round(canvas / 2 - CY * factor)), mask)
    return layer


def circle_cutout(art: Image.Image) -> Image.Image:
    size = art.width * ANTIALIAS
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = art.convert("RGBA")
    out.putalpha(mask.resize(art.size, Image.LANCZOS))
    return out


def ring_picture(art: Image.Image, size: int) -> Image.Image:
    half = 450  # the ring and its glow
    box = (round(CX - half), round(CY - half), round(CX + half), round(CY + half))
    return circle_cutout(art.crop(box).resize((size, size), Image.LANCZOS))


def trace(path: Path, ring_diameter: float, size: int) -> str:
    """Outlines of the ring and the bird in the one-colour picture, as vector path data.

    The picture's ring has its outer edge at x 125..1128 and bottom y 1154, so its centre is
    (626.5, 652.5) and its outer diameter 1003 pixels; that is scaled to ring_diameter.
    """
    cx, cy, diameter = 626.5, 652.5, 1003.0
    mask = (np.array(Image.open(path).convert("RGBA"))[:, :, 3] > 128).astype(np.uint8) * 255
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    scale, origin = ring_diameter / diameter, size / 2
    parts = []
    for contour in sorted(contours, key=cv2.contourArea, reverse=True)[:2]:
        points = cv2.approxPolyDP(contour, 1.0, True)[:, 0, :]
        parts.append("M" + " L".join(f"{(x - cx) * scale + origin:.2f},{(y - cy) * scale + origin:.2f}" for x, y in points) + " Z")
    return " ".join(parts)


def vector(size: int, ring_diameter: float) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
        f'    android:viewportWidth="{size}"\n    android:viewportHeight="{size}">\n'
        '    <path\n        android:fillColor="#FFFFFFFF"\n'
        f'        android:pathData="{trace(MONO_SOURCE, ring_diameter, size)}" />\n'
        '</vector>\n'
    )


def banner(art: Image.Image, font_path: str) -> Image.Image:
    size, ring = (320, 180), 132
    out = Image.new("RGBA", size, BACKGROUND + (255,))
    out.alpha_composite(ring_picture(art, ring), (18, (size[1] - ring) // 2))
    draw = ImageDraw.Draw(out)
    text, left = "ByeByeRKN", 18 + ring + 14
    for px in range(30, 10, -1):
        font = ImageFont.truetype(font_path, px)
        box = draw.textbbox((0, 0), text, font=font)
        if left + box[2] <= size[0] - 14:
            break
    draw.text((left, (size[1] - (box[3] - box[1])) / 2 - box[1]), text, font=font, fill=(255, 255, 255, 255))
    return out


def main() -> None:
    art = Image.open(SOURCE).convert("RGB")
    for name, scale in DENSITIES.items():
        save(launcher_layer(art, scale), RES / f"mipmap-{name}" / "ic_launcher_foreground.webp", quality=92, method=6)

    splash = Image.new("RGBA", (1152, 1152), (0, 0, 0, 0))
    splash.alpha_composite(ring_picture(art, 640), (256, 256))
    save(splash, RES / "drawable-nodpi" / "splash_icon.webp", quality=92, method=6)

    font = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    save(banner(art, font), RES / "drawable-xhdpi" / "tv_banner.png")
    save(art.resize((512, 512), Image.LANCZOS), Path("art/readme.webp"), quality=92, method=6)

    (RES / "drawable" / "ic_tile.xml").write_text(vector(24, 22), encoding="utf-8")
    (RES / "drawable" / "ic_launcher_monochrome.xml").write_text(vector(108, 58), encoding="utf-8")
    print("vectors written")


if __name__ == "__main__":
    main()
