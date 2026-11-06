#!/usr/bin/env python3
"""Puts the screenshots in a plain phone frame, with a status bar, a round punch-hole camera
and the gesture navigation handle, for the README.

screenshots/NN.png -> screenshots/framed/NN.png, on a transparent background so they sit on
light and dark pages alike. The F-Droid screenshots stay unframed.

Usage: python3 scripts/frame_screenshots.py
"""
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "screenshots"
OUT = SRC / "framed"

SCREEN_WIDTH = 540          # screenshots are scaled to this width
BEZEL = 16
BODY_RADIUS = 64
SCREEN_RADIUS = 48
BODY = (28, 28, 30, 255)
EDGE = (72, 72, 76, 255)    # thin outline, so the frame shows on dark pages too
STATUS_BAR = 60             # space above the app for the camera, as on a phone
NAV_AREA = 32               # gesture navigation, over the bottom of the app
CAMERA_RADIUS = 13
CAMERA = (8, 8, 10, 255)
# Roboto, Android's own font, fetched once from Google Fonts
FONT_URL = "https://github.com/google/fonts/raw/main/ofl/roboto/Roboto%5Bwdth,wght%5D.ttf"
FONT = Path.home() / ".cache" / "octolab" / "Roboto.ttf"
TIME = "12:00"
BATTERY = 80
SCALE = 4                   # drawn larger, then scaled down for smooth edges


def rounded_mask(size, radius):
    mask = Image.new("L", (size[0] * SCALE, size[1] * SCALE), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, mask.width - 1, mask.height - 1), radius * SCALE, fill=255)
    return mask.resize(size, Image.LANCZOS)


def clock_font(size, weight="Medium"):
    if not FONT.exists():
        FONT.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(FONT_URL, FONT)
    font = ImageFont.truetype(str(FONT), size)
    font.set_variation_by_name(weight)
    return font


def px(n):
    """Pixels of the 720 px wide reference screenshot, at the drawing size."""
    return n * SCREEN_WIDTH / 720 * SCALE


def bar_from(row, height):
    """A bar of the app's own edge row stretched out, so it matches what's next to it, even a
    half-open drawer; with its average colour and the icon colour that shows on it."""
    bar = row.resize((SCREEN_WIDTH * SCALE, height * SCALE), Image.NEAREST)
    colour = row.resize((1, 1), Image.BOX).getpixel((0, 0))
    light = sum(colour[:3]) / 3 > 140
    return bar, colour, (32, 32, 32) if light else (240, 240, 240), light


def status_bar(top_row):
    """An Android 16 status bar: the time on the left; signal, Wi-Fi and battery with its level
    on the right, sized and placed as on a 720 px wide phone screenshot."""
    bar, colour, fg, light = bar_from(top_row, STATUS_BAR)
    w, h = bar.size
    draw = ImageDraw.Draw(bar)
    dim = (130, 130, 130) if light else (150, 150, 150)
    mid = h / 2

    draw.text((px(62), mid), TIME, font=clock_font(round(px(25))), fill=fg, anchor="lm")

    # Battery: a pill with its level as a number, and a small nub on the right
    right = w - px(60)
    draw.rounded_rectangle((right - px(3), mid - px(5), right, mid + px(5)), px(1.5), fill=dim)
    body_r = right - px(5)
    body_l = body_r - px(42)
    top, bottom = mid - px(10.5), mid + px(10.5)
    draw.rounded_rectangle((body_l, top, body_r, bottom), px(5), fill=dim)
    level = Image.new("L", bar.size, 0)
    ImageDraw.Draw(level).rounded_rectangle((body_l, top, body_r, bottom), px(5), fill=255)
    ImageDraw.Draw(level).rectangle((body_l + px(42) * BATTERY / 100, 0, w, h), fill=0)
    bar.paste(fg, (0, 0, w, h), level)
    draw.text(((body_l + body_r) / 2, mid), str(BATTERY), font=clock_font(round(px(19)), "Bold"),
              fill=colour, anchor="mm")

    # Wi-Fi: a dot and two arcs over it
    right = body_l - px(11)
    cx, base = right - px(13), mid + px(9.5)
    dot = px(2.5)
    draw.ellipse((cx - dot, base - 2 * dot, cx + dot, base), fill=fg)
    for r in (px(10.5), px(17)):
        draw.arc((cx - r, base - px(2) - r, cx + r, base - px(2) + r), 225, 315, fill=fg,
                 width=round(px(3.6)))

    # Signal: four rounded bars, rising; it and Wi-Fi a little shorter than the battery, as on
    # the reference
    right = cx - px(13) - px(11)
    bw, gap = px(3.4), px(2.5)
    bottom = mid + px(9.5)
    for i, height in enumerate((19, 14, 10, 6.5)):
        x = right - (i + 1) * bw - i * gap
        draw.rounded_rectangle((x, bottom - px(height), x + bw, bottom), bw / 2, fill=fg)

    return bar.resize((SCREEN_WIDTH, STATUS_BAR), Image.LANCZOS)


def draw_handle(shot):
    """Android's gesture navigation over the bottom of the app, as on a phone: a half-see-through
    scrim (Android's own, #801b1b1b, or white on a light app), and on it a short rounded handle,
    centred (108 x 4 dp, in the bottom 24 dp, on a 411 dp wide screen)."""
    w, h = shot.size
    hw, hh = w * 108 / 411 / 2, w * 2 / 411
    cy = h - NAV_AREA / 2
    under = shot.crop((0, h - NAV_AREA, w, h))
    light = sum(under.resize((1, 1), Image.BOX).getpixel((0, 0))[:3]) / 3 > 140
    scrim = Image.new("RGBA", under.size, (255, 255, 255, 128) if light else (27, 27, 27, 128))
    shot.paste(Image.alpha_composite(under.convert("RGBA"), scrim).convert("RGB"),
               (0, h - NAV_AREA))
    big = Image.new("RGBA", (w * SCALE, NAV_AREA * SCALE), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle(
        ((w / 2 - hw) * SCALE, (NAV_AREA / 2 - hh) * SCALE,
         (w / 2 + hw) * SCALE, (NAV_AREA / 2 + hh) * SCALE),
        hh * SCALE, fill=(32, 32, 32, 255) if light else (240, 240, 240, 255))
    handle = big.resize((w, NAV_AREA), Image.LANCZOS)
    shot.paste(handle, (0, round(cy - NAV_AREA / 2)), handle)


def frame(path):
    shot = Image.open(path).convert("RGB")
    height = round(shot.height * SCREEN_WIDTH / shot.width)
    shot = shot.resize((SCREEN_WIDTH, height), Image.LANCZOS)

    # A status bar in the app bar's own colour, so the camera covers nothing of the app; the
    # gesture handle sits on the app itself
    draw_handle(shot)
    screen = Image.new("RGB", (SCREEN_WIDTH, height + STATUS_BAR))
    screen.paste(status_bar(shot.crop((0, 0, SCREEN_WIDTH, 1))), (0, 0))
    screen.paste(shot, (0, STATUS_BAR))
    shot, height = screen, screen.height

    w, h = SCREEN_WIDTH + 2 * BEZEL, height + 2 * BEZEL
    big = Image.new("RGBA", (w * SCALE, h * SCALE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(big)
    draw.rounded_rectangle((0, 0, big.width - 1, big.height - 1),
                           BODY_RADIUS * SCALE, fill=EDGE)
    draw.rounded_rectangle((2 * SCALE, 2 * SCALE, big.width - 1 - 2 * SCALE,
                            big.height - 1 - 2 * SCALE),
                           (BODY_RADIUS - 2) * SCALE, fill=BODY)
    framed = big.resize((w, h), Image.LANCZOS)

    framed.paste(shot, (BEZEL, BEZEL), rounded_mask(shot.size, SCREEN_RADIUS))

    # One round punch-hole camera, centred in the status bar
    r = CAMERA_RADIUS
    hole = Image.new("RGBA", (2 * r * SCALE, 2 * r * SCALE), (0, 0, 0, 0))
    ImageDraw.Draw(hole).ellipse((0, 0, hole.width - 1, hole.height - 1), fill=CAMERA)
    hole = hole.resize((2 * r, 2 * r), Image.LANCZOS)
    framed.alpha_composite(hole, (w // 2 - r, BEZEL + STATUS_BAR // 2 - r))

    OUT.mkdir(exist_ok=True)
    framed.save(OUT / path.name, optimize=True)


def main():
    for path in sorted(SRC.glob("[0-9][0-9].png")):
        frame(path)
        print(OUT / path.name)


if __name__ == "__main__":
    main()
