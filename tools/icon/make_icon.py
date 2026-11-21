"""Draws the FTC-Sim icon from scratch: a top-down mecanum robot on a field, with a ball and its shot.

Writes desktop-ui/src/main/resources/dev/ftcsim/ui/icon.png (the window's icon) and app/installer/FTC-Sim.ico
(the installer and its shortcuts). Run: python tools/icon/make_icon.py (needs Pillow).
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SIZE = 1024  # drawn large, then scaled down for smooth edges


def draw() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = SIZE / 256

    def box(x0, y0, x1, y1):
        return [x0 * s, y0 * s, x1 * s, y1 * s]

    # field tile: dark rounded square with a light border
    d.rounded_rectangle(box(8, 8, 248, 248), radius=52 * s, fill=(22, 36, 64, 255))
    d.rounded_rectangle(box(8, 8, 248, 248), radius=52 * s, outline=(70, 110, 190, 255), width=int(6 * s))
    # field lines
    d.line([(40 * s, 128 * s), (216 * s, 128 * s)], fill=(44, 66, 108, 255), width=int(4 * s))
    d.ellipse(box(98, 98, 158, 158), outline=(44, 66, 108, 255), width=int(4 * s))

    # shot trajectory (dashed) from the robot to the ball
    for i in range(5):
        t0, t1 = i / 5, i / 5 + 0.11
        x0, y0 = 128 + 70 * t0, 128 - 70 * t0
        x1, y1 = 128 + 70 * t1, 128 - 70 * t1
        d.line([(x0 * s, y0 * s), (x1 * s, y1 * s)], fill=(255, 176, 48, 255), width=int(6 * s))

    # robot: mecanum wheels, chassis, forward marker
    wheel = (210, 220, 235, 255)
    roller = (95, 120, 165, 255)
    for wx, wy in ((62, 70), (150, 70), (62, 152), (150, 152)):
        d.rounded_rectangle(box(wx, wy, wx + 28, wy + 40), radius=6 * s, fill=wheel)
        for k in range(4):  # angled rollers
            y = wy + 6 + k * 9
            d.line([((wx + 4) * s, (y + 5) * s), ((wx + 24) * s, y * s)], fill=roller, width=int(3 * s))
    d.rounded_rectangle(box(82, 74, 148, 148), radius=12 * s, fill=(236, 240, 248, 255))
    d.rounded_rectangle(box(92, 84, 138, 138), radius=8 * s, fill=(60, 110, 220, 255))
    d.polygon([(115 * s, 90 * s), (128 * s, 108 * s), (102 * s, 108 * s)], fill=(236, 240, 248, 255))

    # ball being shot
    d.ellipse(box(182, 38, 226, 82), fill=(255, 176, 48, 255))
    d.ellipse(box(190, 46, 204, 60), fill=(255, 214, 140, 255))
    return img


def main() -> None:
    img = draw()
    window = ROOT / "desktop-ui/src/main/resources/dev/ftcsim/ui/icon.png"
    img.resize((256, 256), Image.LANCZOS).save(window)
    ico = ROOT / "app/installer/FTC-Sim.ico"
    img.save(ico, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print(window, ico)


if __name__ == "__main__":
    main()
