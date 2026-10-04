#!/usr/bin/env python3
"""Regenerates app/src/main/assets/sample_paper.json.

The sample bank embeds its pictures as base64 PNG data URIs, which makes the
asset unreviewable by hand and impossible to edit safely. This script owns both
halves: it draws the figures with a tiny built-in PNG encoder (no Pillow on the
build host) and writes the JSON that ships in the APK.

    python3 tools/gen_sample_paper.py

Deterministic: the same input always produces byte-identical output, so a
regenerated asset shows up in git only when a figure or a question really
changed.

Between them the questions exercise every shape the importer understands:
plain strings, structured elements, MathML formulas, chemical equations,
tables in the question / options / explanation, pictures in all three, single
and multiple correct answers, English and Hindi, plus deliberate edge cases.
"""

import base64
import json
import math
import os
import re
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "sample_paper.json")

# --------------------------------------------------------------------------
# A 5x7 bitmap font. Enough for the axis labels and tick numbers the figures
# need, and it keeps the PNGs small because glyphs compress well.
# --------------------------------------------------------------------------

FONT = {
    "0": ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.",
    "1": "..#..|.##..|..#..|..#..|..#..|..#..|.###.",
    "2": ".###.|#...#|....#|...#.|..#..|.#...|#####",
    "3": "#####|...#.|..#..|...#.|....#|#...#|.###.",
    "4": "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
    "5": "#####|#....|####.|....#|....#|#...#|.###.",
    "6": "..##.|.#...|#....|####.|#...#|#...#|.###.",
    "7": "#####|....#|...#.|..#..|.#...|.#...|.#...",
    "8": ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
    "9": ".###.|#...#|#...#|.####|....#|...#.|.##..",
    "A": ".###.|#...#|#...#|#####|#...#|#...#|#...#",
    "B": "####.|#...#|#...#|####.|#...#|#...#|####.",
    "C": ".###.|#...#|#....|#....|#....|#...#|.###.",
    "D": "####.|#...#|#...#|#...#|#...#|#...#|####.",
    "E": "#####|#....|#....|####.|#....|#....|#####",
    "F": "#####|#....|#....|####.|#....|#....|#....",
    "G": ".###.|#...#|#....|#.###|#...#|#...#|.###.",
    "H": "#...#|#...#|#...#|#####|#...#|#...#|#...#",
    "I": ".###.|..#..|..#..|..#..|..#..|..#..|.###.",
    "J": "..###|...#.|...#.|...#.|...#.|#..#.|.##..",
    "K": "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#",
    "L": "#....|#....|#....|#....|#....|#....|#####",
    "M": "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
    "N": "#...#|##..#|#.#.#|#..##|#...#|#...#|#...#",
    "O": ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
    "P": "####.|#...#|#...#|####.|#....|#....|#....",
    "Q": ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#",
    "R": "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
    "S": ".####|#....|#....|.###.|....#|....#|####.",
    "T": "#####|..#..|..#..|..#..|..#..|..#..|..#..",
    "U": "#...#|#...#|#...#|#...#|#...#|#...#|.###.",
    "V": "#...#|#...#|#...#|#...#|#...#|.#.#.|..#..",
    "W": "#...#|#...#|#...#|#.#.#|#.#.#|##.##|#...#",
    "X": "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#",
    "Y": "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..",
    "Z": "#####|....#|...#.|..#..|.#...|#....|#####",
    " ": ".....|.....|.....|.....|.....|.....|.....",
    ".": ".....|.....|.....|.....|.....|.##..|.##..",
    ",": ".....|.....|.....|.....|.##..|.##..|.#...",
    ":": ".....|.##..|.##..|.....|.##..|.##..|.....",
    "-": ".....|.....|.....|#####|.....|.....|.....",
    "+": ".....|..#..|..#..|#####|..#..|..#..|.....",
    "/": "....#|....#|...#.|..#..|.#...|#....|#....",
    "(": "..##.|.#...|#....|#....|#....|.#...|..##.",
    ")": ".##..|...#.|....#|....#|....#|...#.|.##..",
    "%": "##..#|##.#.|...#.|..#..|.#...|#.##.|#.##.",
    "=": ".....|.....|#####|.....|#####|.....|.....",
    "?": ".###.|#...#|....#|...#.|..#..|.....|..#..",
    "'": "..#..|..#..|.....|.....|.....|.....|.....",
}

WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
GREY = (128, 128, 128)
LIGHT = (222, 226, 230)
RED = (200, 40, 40)
BLUE = (40, 90, 190)
GREEN = (40, 150, 70)
AMBER = (230, 160, 30)
BROWN = (140, 90, 50)


class Canvas:
    """A tiny RGB raster with just the primitives the figures need."""

    def __init__(self, w, h, bg=WHITE):
        self.w, self.h = w, h
        self.px = bytearray(bytes(bg) * (w * h))

    def _set(self, x, y, colour):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 3
            self.px[i : i + 3] = bytes(colour)

    def rect(self, x0, y0, x1, y1, colour):
        for y in range(int(y0), int(y1) + 1):
            for x in range(int(x0), int(x1) + 1):
                self._set(x, y, colour)

    def frame(self, x0, y0, x1, y1, colour, weight=1):
        for t in range(weight):
            self.rect(x0 + t, y0 + t, x1 - t, y0 + t, colour)
            self.rect(x0 + t, y1 - t, x1 - t, y1 - t, colour)
            self.rect(x0 + t, y0 + t, x0 + t, y1 - t, colour)
            self.rect(x1 - t, y0 + t, x1 - t, y1 - t, colour)

    def line(self, x0, y0, x1, y1, colour, weight=1):
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            for ox in range(weight):
                for oy in range(weight):
                    self._set(x0 + ox, y0 + oy, colour)
            if x0 == x1 and y0 == y1:
                return
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def disc(self, cx, cy, r, colour):
        cx, cy, r = int(cx), int(cy), int(r)
        rr = r * r
        for y in range(cy - r, cy + r + 1):
            dy = y - cy
            for x in range(cx - r, cx + r + 1):
                if (x - cx) ** 2 + dy * dy <= rr:
                    self._set(x, y, colour)

    def ring(self, cx, cy, r, colour, weight=2):
        cx, cy, r = int(cx), int(cy), int(r)
        outer, inner = r * r, (r - weight) ** 2
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                d = (x - cx) ** 2 + (y - cy) ** 2
                if inner <= d <= outer:
                    self._set(x, y, colour)

    def polygon(self, points, colour, weight=1):
        for i in range(len(points)):
            x0, y0 = points[i]
            x1, y1 = points[(i + 1) % len(points)]
            self.line(x0, y0, x1, y1, colour, weight)

    def fill_polygon(self, points, colour):
        ys = [p[1] for p in points]
        for y in range(int(min(ys)), int(max(ys)) + 1):
            crossings = []
            for i in range(len(points)):
                x0, y0 = points[i]
                x1, y1 = points[(i + 1) % len(points)]
                if (y0 <= y < y1) or (y1 <= y < y0):
                    crossings.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
            crossings.sort()
            for i in range(0, len(crossings) - 1, 2):
                self.rect(
                    math.ceil(crossings[i]), y, math.floor(crossings[i + 1]), y, colour
                )

    def sector(self, cx, cy, r, start_deg, end_deg, colour):
        cx, cy = int(cx), int(cy)
        r2 = r * r
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                dx, dy = x - cx, y - cy
                if dx * dx + dy * dy > r2:
                    continue
                # Screen y grows downwards, so angles are measured clockwise.
                deg = math.degrees(math.atan2(dy, dx)) % 360
                lo, hi = start_deg % 360, end_deg % 360
                inside = lo <= deg < hi if lo < hi else (deg >= lo or deg < hi)
                if inside:
                    self._set(x, y, colour)

    def text(self, x, y, s, colour=BLACK, scale=2, spacing=1):
        cursor = x
        for ch in s.upper():
            rows = FONT.get(ch, FONT["?"]).split("|")
            for ry, row in enumerate(rows):
                for rx, cell in enumerate(row):
                    if cell == "#":
                        self.rect(
                            cursor + rx * scale,
                            y + ry * scale,
                            cursor + (rx + 1) * scale - 1,
                            y + (ry + 1) * scale - 1,
                            colour,
                        )
            cursor += (5 + spacing) * scale
        return cursor

    def text_width(self, s, scale=2, spacing=1):
        return len(s) * (5 + spacing) * scale - spacing * scale

    def png(self):
        raw = bytearray()
        stride = self.w * 3
        for y in range(self.h):
            raw.append(0)  # filter type: None
            raw += self.px[y * stride : (y + 1) * stride]

        def chunk(tag, data):
            body = tag + data
            return (
                struct.pack(">I", len(data))
                + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
            )

        ihdr = struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0)
        return (
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b"")
        )


def data_uri(canvas):
    return "data:image/png;base64," + base64.b64encode(canvas.png()).decode("ascii")


# --------------------------------------------------------------------------
# Figures
# --------------------------------------------------------------------------


def bar_chart():
    c = Canvas(240, 156)
    left, base, right = 34, 126, 228
    c.line(left, 16, left, base, BLACK, 2)
    c.line(left, base, right, base, BLACK, 2)
    for value in range(0, 101, 20):
        y = base - int(value / 100 * (base - 18))
        c.line(left, y, right, y, LIGHT)
        c.text(6, y - 7, str(value), GREY, 1)
    # Deliberately uneven so "tallest" and "shortest" are unambiguous.
    bars = [("A", 30, BLUE), ("B", 70, GREEN), ("C", 45, AMBER), ("D", 90, RED)]
    slot = (right - left - 12) // len(bars)
    for i, (label, value, colour) in enumerate(bars):
        x0 = left + 6 + i * slot
        top = base - int(value / 100 * (base - 18))
        c.rect(x0, top, x0 + slot - 12, base - 1, colour)
        c.frame(x0, top, x0 + slot - 12, base - 1, BLACK)
        c.text(
            x0 + (slot - 12 - c.text_width(label, 1)) // 2, base + 5, label, BLACK, 1
        )
    c.text(left, 2, "SALES 2025", BLACK, 1)
    return c


def pie_sectors():
    c = Canvas(150, 150)
    cx = cy = 74
    r = 60
    # 35 / 25 / 25 / 15 percent, clockwise from twelve o'clock. B and C are
    # equal, which is what the question asks the reader to spot.
    c.sector(cx, cy, r, -90, 36, BLUE)
    c.sector(cx, cy, r, 36, 126, GREEN)
    c.sector(cx, cy, r, 126, 216, AMBER)
    c.sector(cx, cy, r, 216, 270, RED)
    c.ring(cx, cy, r, BLACK, 2)
    c.line(cx, cy, cx, cy - r, BLACK, 1)
    c.line(
        cx,
        cy,
        cx + int(r * math.cos(math.radians(36))),
        cy + int(r * math.sin(math.radians(36))),
        BLACK,
        1,
    )
    c.line(
        cx,
        cy,
        cx + int(r * math.cos(math.radians(126))),
        cy + int(r * math.sin(math.radians(126))),
        BLACK,
        1,
    )
    c.line(
        cx,
        cy,
        cx + int(r * math.cos(math.radians(216))),
        cy + int(r * math.sin(math.radians(216))),
        BLACK,
        1,
    )
    labels = [("A", -27, BLUE), ("B", 81, GREEN), ("C", 171, AMBER), ("D", 243, RED)]
    for text, deg, _ in labels:
        rad = math.radians(deg)
        lx = cx + int((r * 0.62) * math.cos(rad))
        ly = cy + int((r * 0.62) * math.sin(rad))
        c.text(lx - 3, ly - 7, text, WHITE, 2)
    c.text(40, 142, "SHARE", BLACK, 1)
    return c


def clock_face():
    c = Canvas(150, 150)
    cx = cy = 75
    c.disc(cx, cy, 68, WHITE)
    c.ring(cx, cy, 68, BLACK, 3)
    for hour in range(12):
        ang = math.radians(hour * 30 - 90)
        r0 = 56 if hour % 3 == 0 else 60
        c.line(
            cx + r0 * math.cos(ang),
            cy + r0 * math.sin(ang),
            cx + 64 * math.cos(ang),
            cy + 64 * math.sin(ang),
            BLACK,
            2 if hour % 3 == 0 else 1,
        )
    for label, hour in (("12", 0), ("3", 3), ("6", 6), ("9", 9)):
        ang = math.radians(hour * 30 - 90)
        c.text(
            cx + 44 * math.cos(ang) - c.text_width(label, 1) // 2,
            cy + 44 * math.sin(ang) - 3,
            label,
            BLACK,
            1,
        )
    c.disc(cx, cy, 4, BLACK)
    # Three o'clock: the hour hand points at 3, the minute hand at 12.
    c.line(cx, cy, cx + 30, cy, BLACK, 4)
    c.line(cx, cy, cx, cy - 46, BLACK, 3)
    return c


def water_molecule():
    c = Canvas(190, 132)
    c.text(4, 4, "ONE MOLECULE OF WATER", BLACK, 1)
    ox, oy = 95, 74
    c.line(ox, oy, ox - 44, oy - 30, GREY, 3)
    c.line(ox, oy, ox + 44, oy - 30, GREY, 3)
    c.disc(ox - 44, oy - 30, 13, WHITE)
    c.ring(ox - 44, oy - 30, 13, BLACK, 2)
    c.text(ox - 47, oy - 37, "H", BLACK, 2)
    c.disc(ox + 44, oy - 30, 13, WHITE)
    c.ring(ox + 44, oy - 30, 13, BLACK, 2)
    c.text(ox + 41, oy - 37, "H", BLACK, 2)
    c.disc(ox, oy, 22, RED)
    c.ring(ox, oy, 22, BLACK, 2)
    c.text(ox - 5, oy - 10, "O", WHITE, 3)
    return c


def right_triangle():
    c = Canvas(190, 152)
    c.text(4, 4, "TRIANGLE ABC", BLACK, 1)
    ax, ay = 34, 120
    bx, by = 152, 120
    cx, cy = 34, 30
    c.fill_polygon([(ax, ay), (bx, by), (cx, cy)], (243, 246, 250))
    c.polygon([(ax, ay), (bx, by), (cx, cy)], BLACK, 2)
    # Right-angle square at A.
    c.line(ax + 16, ay, ax + 16, ay - 16, BLACK, 1)
    c.line(ax, ay - 16, ax + 16, ay - 16, BLACK, 1)
    c.text(ax - 20, ay - 8, "A", BLACK, 2)
    c.text(bx + 6, by - 8, "B", BLACK, 2)
    c.text(cx - 6, cy - 20, "C", BLACK, 2)
    c.text(ax + 22, ay - 32, "90", BLACK, 1)
    return c


def spectrum():
    c = Canvas(238, 62)
    c.text(4, 2, "VISIBLE SPECTRUM", BLACK, 1)
    bands = [
        ("V", (120, 90, 200)),
        ("I", (60, 60, 200)),
        ("B", (30, 90, 210)),
        ("G", (40, 170, 80)),
        ("Y", (235, 220, 60)),
        ("O", (235, 140, 40)),
        ("R", (210, 50, 40)),
    ]
    x = 4
    width = (238 - 8) // len(bands)
    for label, colour in bands:
        c.rect(x, 18, x + width - 2, 44, colour)
        c.frame(x, 18, x + width - 2, 44, BLACK)
        c.text(x + (width - 2 - c.text_width(label, 1)) // 2, 48, label, BLACK, 1)
        x += width
    return c


def number_line():
    c = Canvas(238, 74)
    c.text(4, 2, "NUMBER LINE", BLACK, 1)
    x0, x1, y = 16, 222, 44
    c.line(x0, y, x1, y, BLACK, 2)
    for value in range(0, 11):
        x = x0 + (x1 - x0) * value / 10
        c.line(x, y - 5, x, y + 5, BLACK, 2)
        c.text(x - c.text_width(str(value), 1) // 2, y + 10, str(value), BLACK, 1)
    # The marker sits halfway between 3 and 4.
    x = x0 + (x1 - x0) * 3.5 / 10
    c.line(x, y - 22, x, y - 6, RED, 2)
    c.disc(x, y - 24, 5, RED)
    return c


def _shape(draw):
    c = Canvas(76, 76)
    c.frame(0, 0, 75, 75, LIGHT)
    draw(c)
    return c


def shape_square():
    c = _shape(
        lambda cv: (
            cv.rect(18, 18, 57, 57, (200, 220, 250)),
            cv.frame(18, 18, 57, 57, BLACK, 2),
        )
    )
    return c


def shape_rectangle():
    c = _shape(
        lambda cv: (
            cv.rect(10, 24, 65, 51, (250, 225, 200)),
            cv.frame(10, 24, 65, 51, BLACK, 2),
        )
    )
    return c


def shape_triangle():
    c = _shape(
        lambda cv: (
            cv.fill_polygon([(38, 14), (64, 60), (12, 60)], (215, 240, 215)),
            cv.polygon([(38, 14), (64, 60), (12, 60)], BLACK, 2),
        )
    )
    return c


def shape_pentagon():
    pts = [
        (38 + 26 * math.cos(math.radians(a)), 38 + 26 * math.sin(math.radians(a)))
        for a in (-90, -18, 54, 126, 198)
    ]
    c = _shape(
        lambda cv: (cv.fill_polygon(pts, (240, 215, 240)), cv.polygon(pts, BLACK, 2))
    )
    return c


def ship():
    c = Canvas(170, 120)
    c.text(4, 4, "OCEAN LINER", BLACK, 1)
    # Hull, funnel and two masts: recognisable without any fine detail.
    c.fill_polygon([(24, 74), (146, 74), (132, 96), (38, 96)], (60, 60, 70))
    c.polygon([(24, 74), (146, 74), (132, 96), (38, 96)], BLACK, 2)
    c.rect(72, 50, 92, 74, (200, 60, 60))
    c.frame(72, 50, 92, 74, BLACK)
    c.line(58, 74, 58, 26, BLACK, 2)
    c.fill_polygon([(58, 28), (58, 70), (94, 70)], (240, 240, 245))
    c.polygon([(58, 28), (58, 70), (94, 70)], BLACK, 1)
    c.line(112, 74, 112, 36, BLACK, 2)
    c.fill_polygon([(112, 38), (112, 70), (140, 70)], (240, 240, 245))
    c.polygon([(112, 38), (112, 70), (140, 70)], BLACK, 1)
    for x in range(8, 166, 12):
        c.line(x, 108, x + 6, 108, (70, 120, 190), 2)
    return c


FIGURES = {
    "bar_chart": bar_chart(),
    "pie_sectors": pie_sectors(),
    "clock": clock_face(),
    "water_molecule": water_molecule(),
    "right_triangle": right_triangle(),
    "spectrum": spectrum(),
    "number_line": number_line(),
    "ship": ship(),
    "shape_square": shape_square(),
    "shape_rectangle": shape_rectangle(),
    "shape_triangle": shape_triangle(),
    "shape_pentagon": shape_pentagon(),
}
IMG = {name: data_uri(canvas) for name, canvas in FIGURES.items()}


def img(name, alt):
    """An image content element; content must be an <img> tag for src extraction."""
    return {"type": "image", "content": '<img src="%s" alt="%s">' % (IMG[name], alt)}


def txt(s):
    return {"type": "text", "content": s}


def math(s):
    return {"type": "math", "content": "<math>%s</math>" % s}


def table(rows):
    return {"type": "table", "content": rows}


# --------------------------------------------------------------------------
# MathML snippets
# --------------------------------------------------------------------------

M_H2O = (
    "<mn>2</mn><msub><mi>H</mi><mn>2</mn></msub><mo>+</mo>"
    "<msub><mi>O</mi><mn>2</mn></msub><mo>&#x2192;</mo>"
    "<mn>2</mn><msub><mi>H</mi><mn>2</mn></msub><mi>O</mi>"
)
M_H2O2 = (
    "<mn>2</mn><msub><mi>H</mi><mn>2</mn></msub><mo>+</mo>"
    "<msub><mi>O</mi><mn>2</mn></msub><mo>&#x2192;</mo>"
    "<mn>2</mn><msub><mi>H</mi><mn>2</mn></msub><msub><mi>O</mi><mn>2</mn></msub>"
)
M_NACL = "<mi>Na</mi><mo>+</mo><mi>Cl</mi><mo>&#x2192;</mo><mi>NaCl</mi>"
M_CO = (
    "<mi>C</mi><mo>+</mo><msub><mi>O</mi><mn>2</mn></msub><mo>&#x2192;</mo><mi>CO</mi>"
)
M_PHOTOSYNTHESIS = (
    "<mn>6</mn><msub><mi>CO</mi><mn>2</mn></msub><mo>+</mo><mn>6</mn>"
    "<msub><mi>H</mi><mn>2</mn></msub><mi>O</mi><mo>&#x2192;</mo>"
    "<msub><mi>C</mi><mn>6</mn></msub><msub><mi>H</mi><mn>12</mn></msub>"
    "<msub><mi>O</mi><mn>6</mn></msub><mo>+</mo><mn>6</mn>"
    "<msub><mi>H</mi><mn>2</mn></msub><mi>O</mi>"
)
M_QUADRATIC = (
    "<mi>x</mi><mo>=</mo><mfrac><mrow><mo>&#x2212;</mo><mi>b</mi>"
    "<mo>&#x00B1;</mo><msqrt><mrow><msup><mi>b</mi><mn>2</mn></msup>"
    "<mo>&#x2212;</mo><mn>4</mn><mi>a</mi><mi>c</mi></mrow></msqrt></mrow>"
    "<mrow><mn>2</mn><mi>a</mi></mrow></mfrac>"
)
M_VUAT = "<mi>v</mi><mo>=</mo><mi>u</mi><mo>+</mo><mi>a</mi><mi>t</mi>"
M_EINSTEIN = "<mi>E</mi><mo>=</mo><mi>m</mi><msup><mi>c</mi><mn>2</mn></msup>"
M_OHMS = "<mi>V</mi><mo>=</mo><mi>I</mi><mi>R</mi>"
M_CIRCLE = "<mi>A</mi><mo>=</mo><mi>&#x03C0;</mi><msup><mi>r</mi><mn>2</mn></msup>"
M_PYTHAGORAS = (
    "<msup><mi>c</mi><mn>2</mn></msup><mo>=</mo>"
    "<msup><mi>a</mi><mn>2</mn></msup><mo>+</mo>"
    "<msup><mi>b</mi><mn>2</mn></msup>"
)
M_NAIVE = "<mi>d</mi><mo>=</mo><mfrac><mi>t</mi><mi>s</mi></mfrac>"
M_KEPLER = (
    "<msup><mi>T</mi><mn>2</mn></msup><mo>&#x221D;</mo>"
    "<msup><mi>a</mi><mn>3</mn></msup>"
)
M_SOLAR = "<mi>E</mi><mo>=</mo><mi>h</mi><mi>&#x03BD;</mi>"
M_DENSITY = "<mi>&#x03C1;</mi><mo>=</mo><mfrac><mi>m</mi><mi>V</mi></mfrac>"
M_CUBIC = (
    "<mi>f</mi><mo>(</mo><mi>x</mi><mo>)</mo><mo>=</mo>"
    "<msup><mi>x</mi><mn>3</mn></msup><mo>&#x2212;</mo>"
    "<mn>6</mn><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mn>9</mn><mi>x</mi>"
)
M_DERIV = (
    "<msup><mi>f</mi><mo>&prime;</mo></msup><mo>(</mo><mi>x</mi><mo>)</mo><mo>=</mo>"
    "<mn>3</mn><msup><mi>x</mi><mn>2</mn></msup><mo>&#x2212;</mo>"
    "<mn>12</mn><mi>x</mi><mo>+</mo><mn>9</mn>"
)
M_FACTOR = (
    "<mn>3</mn><mo>(</mo><mi>x</mi><mo>&#x2212;</mo><mn>1</mn><mo>)</mo>"
    "<mo>(</mo><mi>x</mi><mo>&#x2212;</mo><mn>3</mn><mo>)</mo>"
)
M_AP_SUM = (
    "<msub><mi>S</mi><mi>n</mi></msub><mo>=</mo><mfrac><mi>n</mi><mn>2</mn></mfrac>"
    "<mo>(</mo><mn>2</mn><mi>a</mi><mo>+</mo><mo>(</mo><mi>n</mi><mo>&#x2212;</mo>"
    "<mn>1</mn><mo>)</mo><mi>d</mi><mo>)</mo>"
)
M_SEVEN = (
    "<mi>P</mi><mo>=</mo><mfrac><mi>w</mi><mn>36</mn></mfrac>"
    "<mo>=</mo><mfrac><mn>6</mn><mn>36</mn></mfrac>"
)
M_GRAVITY = (
    "<mi>a</mi><mo>=</mo><mi>g</mi><mo>&#x2248;</mo><mn>9.8</mn>"
    "<mfrac><mi>m</mi><msup><mi>s</mi><mn>2</mn></msup></mfrac>"
)


# --------------------------------------------------------------------------
# The bank
# --------------------------------------------------------------------------


def _elements(value):
    """Coerce loose input into a list of content elements.

    `*_elements` fields take element *objects*; a bare string is silently
    dropped by the importer, leaving blank option text. Wrapping strings here
    keeps the question definitions below readable without tripping that.
    """
    if isinstance(value, str):
        value = [value]
    return [txt(item) if isinstance(item, str) else item for item in (value or [])]


def q(
    qid,
    elements,
    options,
    correct,
    explanation,
    *,
    difficulty="medium",
    marks=1,
    tags=None,
    image=None,
    explanation_image=None,
):
    question = {
        "id": qid,
        "question_elements": _elements(elements),
        "options_elements": {key: _elements(value) for key, value in options.items()},
        "explanation_elements": _elements(explanation),
        "difficulty": difficulty,
        "marks": marks,
        "tags": tags or [],
    }
    if correct:
        question["correctOptionIds"] = correct
    # The older scalar picture fields, kept working alongside the elements.
    if image:
        question["image"] = image
    if explanation_image:
        question["explanationImage"] = explanation_image
    return question


CATEGORIES = [
    {
        "id": "cat-science",
        "title": "Science",
        "questions": [
            q(
                "q-sci-1",
                [txt("Which two statements about the states of matter are correct?")],
                {
                    "a": [txt("A gas has a fixed volume but no fixed shape.")],
                    "b": [
                        txt(
                            "A liquid keeps its volume but takes the shape of its container."
                        )
                    ],
                    "c": [txt("A solid has both a fixed shape and a fixed volume.")],
                    "d": [txt("All three states are compressible to the same degree.")],
                },
                ["b", "c"],
                [
                    txt(
                        "Liquids and solids both have definite volume, and only solids have a "
                        "definite shape. Gases have neither, and they compress readily, so (a) "
                        "and (d) are both wrong."
                    )
                ],
                difficulty="easy",
                tags=["states-of-matter", "multi-correct"],
            ),
            q(
                "q-sci-2",
                [
                    txt(
                        "The diagram shows one molecule of water. How many atoms "
                        "does it contain?"
                    ),
                    img(
                        "water_molecule",
                        "One water molecule: two hydrogen atoms bonded to one oxygen atom",
                    ),
                ],
                {"a": [txt("1")], "b": [txt("2")], "c": [txt("3")], "d": [txt("6")]},
                ["c"],
                [
                    txt(
                        "The picture shows a single molecule made of one oxygen atom and two "
                        "hydrogen atoms, so three atoms in total. One molecule is the smallest "
                        "unit that still has the formula "
                    ),
                    math("<msub><mi>H</mi><mn>2</mn></msub><mi>O</mi>"),
                    txt("."),
                ],
                difficulty="easy",
                tags=["atoms", "image-question"],
            ),
        ],
    },
    {
        "id": "cat-chemistry",
        "title": "Chemistry",
        "parentId": "cat-science",
        "questions": [
            q(
                "q-chem-1",
                [txt("Which of these chemical equations are balanced?")],
                {
                    "a": [math(M_H2O)],
                    "b": [math(M_H2O2)],
                    "c": [math(M_NACL)],
                    "d": [math(M_CO)],
                },
                ["a", "c"],
                [
                    math(M_H2O),
                    txt(
                        " balances: four hydrogen atoms and two oxygen atoms on "
                        "each side. "
                    ),
                    math(M_NACL),
                    txt(
                        " balances with one atom of each element on both sides. "
                        "The other two do not conserve atoms."
                    ),
                ],
                difficulty="hard",
                marks=2,
                tags=["equations", "multi-correct"],
            ),
            q(
                "q-chem-2",
                [
                    txt(
                        "Use the table to answer: which element has the atomic number 17?"
                    ),
                    table(
                        [
                            ["Atomic number", "Symbol", "Element name"],
                            ["8", "O", "Oxygen"],
                            ["11", "Na", "Sodium"],
                            ["17", "Cl", "Chlorine"],
                            ["26", "Fe", "Iron"],
                        ]
                    ),
                ],
                {"a": ["Oxygen"], "b": ["Sodium"], "c": ["Chlorine"], "d": ["Iron"]},
                ["c"],
                [
                    txt(
                        "Reading across the row that starts with 17 gives the symbol Cl and the "
                        "name Chlorine. Atomic numbers increase with the number of protons, so "
                        "17 protons is chlorine."
                    )
                ],
                difficulty="easy",
                tags=["periodic-table", "table-question"],
            ),
            q(
                "q-chem-3",
                [
                    txt(
                        "The table lists four acids. Which two are mineral (inorganic) acids "
                        "rather than organic acids?"
                    ),
                    table(
                        [
                            ["Acid", "Formula", "Class"],
                            ["Sulfuric", "H₂SO₄", "Mineral"],
                            ["Acetic", "CH₃COOH", "Organic"],
                            ["Nitric", "HNO₃", "Mineral"],
                            ["Citric", "C₆H₈O₇", "Organic"],
                        ]
                    ),
                ],
                {
                    "a": ["Sulfuric only"],
                    "b": ["Acetic only"],
                    "c": ["Sulfuric and Nitric"],
                    "d": ["Citric only"],
                },
                ["c"],
                [
                    txt(
                        "Sulfuric and nitric acids contain no carbon and are classed as mineral "
                        "acids. Acetic and citric acids are organic because they are built on a "
                        "carbon chain."
                    )
                ],
                difficulty="medium",
                tags=["acids", "table-question"],
            ),
            q(
                "q-chem-4",
                [
                    txt("रासायनिक संयोजन में क्या संरक्षित रहता है?"),
                    txt("(Which quantity is conserved in a chemical reaction?)"),
                ],
                {
                    "a": [txt("केवल द्रव्यमान")],
                    "b": [txt("केवल ऊर्जा")],
                    "c": [txt("द्रव्यमान और आवेश दोनों")],
                    "d": [txt("केवल आयतन")],
                },
                ["c"],
                [
                    txt(
                        "रासायनिक संयोजन में परमाणुओं की संख्या बदलती नहीं है, इसलिए द्रव्यमान और "
                        "आवेश दोनों संरक्षित रहते हैं।"
                    ),
                    math(M_H2O),
                    txt(" में बाईं और दाईं ओर परमाणुओं की गिनती समान है।"),
                ],
                difficulty="medium",
                tags=["hindi", "conservation"],
            ),
        ],
    },
    {
        "id": "cat-physics",
        "title": "Physics",
        "parentId": "cat-science",
        "questions": [
            q(
                "q-phy-1",
                [
                    txt(
                        "A car starts from rest and accelerates at a constant rate. Using "
                    ),
                    math(M_VUAT),
                    txt(", which quantities stay constant?"),
                ],
                {
                    "a": [txt("The velocity")],
                    "b": [txt("The acceleration")],
                    "c": [txt("The starting velocity u")],
                    "d": [txt("The time elapsed")],
                },
                ["c"],
                [
                    math(M_VUAT),
                    txt(
                        " treats u and a as fixed inputs, so both stay constant, "
                        "while the velocity grows from zero and the elapsed time keeps "
                        "increasing. Two worked rows:"
                    ),
                    table(
                        [
                            ["u (m/s)", "a (m/s²)", "t (s)", "v (m/s)"],
                            ["0", "2", "3", "6"],
                            ["4", "2", "3", "10"],
                        ]
                    ),
                    txt(
                        " Only the last column changes between rows, which is exactly "
                        "why velocity cannot be the constant."
                    ),
                ],
                difficulty="medium",
                tags=["kinematics", "formula"],
            ),
            q(
                "q-phy-2",
                [
                    txt("What time does the clock show?"),
                    img("clock", "An analogue clock showing three o'clock"),
                ],
                {
                    "a": [txt("3:00")],
                    "b": [txt("12:15")],
                    "c": [txt("6:00")],
                    "d": [txt("9:45")],
                },
                ["a"],
                [
                    txt(
                        "The short hour hand points at 3 and the long minute hand points at "
                        "12, which is three o'clock."
                    )
                ],
                difficulty="easy",
                tags=["clock", "image-question"],
            ),
            q(
                "q-phy-3",
                [
                    txt(
                        "The spectrum runs from violet down to red. Which formula links "
                        "the energy of a single photon to its frequency?"
                    ),
                    img("spectrum", "The visible spectrum from violet to red"),
                ],
                {
                    "a": [math(M_SOLAR)],
                    "b": [math(M_EINSTEIN)],
                    "c": [math(M_OHMS)],
                    "d": [math(M_DENSITY)],
                },
                ["a"],
                [
                    math(M_SOLAR),
                    txt(
                        " ties a photon's energy to its frequency, where h is Planck's "
                        "constant. "
                    ),
                    math(M_EINSTEIN),
                    txt(
                        " is mass-energy equivalence and says nothing about frequency. "
                        "Red sits at the lowest-frequency end of the spectrum shown, so "
                        "it carries the least energy per photon."
                    ),
                ],
                difficulty="hard",
                marks=2,
                tags=["photons", "formula", "image-question"],
            ),
        ],
    },
    {
        "id": "cat-mathematics",
        "title": "Mathematics",
        "questions": [
            q(
                "q-math-1",
                [
                    txt("The roots of a quadratic equation are given by "),
                    math(M_QUADRATIC),
                    txt(". Which expression is the sum of the two roots?"),
                ],
                {
                    "a": [txt("−b/a")],
                    "b": [txt("b/a")],
                    "c": [txt("−c/a")],
                    "d": [txt("2a")],
                },
                ["a"],
                [
                    txt("Adding the two branches cancels the square root and leaves "),
                    math("<mo>&#x2212;</mo><mfrac><mi>b</mi><mi>a</mi></mfrac>"),
                    txt(", which is the standard sum-of-roots result."),
                ],
                difficulty="hard",
                marks=2,
                tags=["quadratic", "formula"],
            ),
            q(
                "q-math-2",
                [
                    txt("In the bar chart, which two bars are taller than bar C?"),
                    img("bar_chart", "Bar chart of 2025 sales with bars A to D"),
                ],
                {
                    "a": [txt("A and D")],
                    "b": [txt("B and D")],
                    "c": [txt("B only")],
                    "d": [txt("All except A")],
                },
                ["b"],
                [
                    txt(
                        "Bar C stands at 45. Bar B reaches 70 and bar D reaches 90, so both "
                        "clear it, while bar A at 30 does not."
                    )
                ],
                difficulty="easy",
                tags=["charts", "image-question", "multi-correct"],
            ),
            q(
                "q-math-3",
                [
                    txt(
                        "A car covers 240 km in 4 hours and then 150 km in 2 hours. What "
                        "is its average speed for the whole journey?"
                    )
                ],
                {
                    "a": ["60 km/h"],
                    "b": ["65 km/h"],
                    "c": ["70 km/h"],
                    "d": ["75 km/h"],
                },
                ["b"],
                [
                    table(
                        [
                            ["Leg", "Distance", "Time", "Speed"],
                            ["First", "240 km", "4 h", "60 km/h"],
                            ["Second", "150 km", "2 h", "75 km/h"],
                            ["Total", "390 km", "6 h", "65 km/h"],
                        ]
                    ),
                    txt(
                        " Average speed is total distance over total time, 390 ÷ 6, which is "
                        "65 km/h. Taking the mean of the two leg speeds instead gives "
                        "67.5 km/h, which is wrong because the faster leg covered less time."
                    ),
                ],
                difficulty="medium",
                tags=["speed", "table-explanation"],
            ),
            q(
                "q-math-4",
                [txt("भारतीय संख्या पद्धति में 47 का अंतिम अंक क्या है?")],
                {"a": ["7"], "b": ["4"], "c": ["11"], "d": ["1"]},
                ["a"],
                [
                    txt(
                        "दशमलव प्रणाली में संख्या 47 के दाहिने सातवाँ अंक इकाई का है, इसलिए "
                        "अंतिम अंक 7 है।"
                    )
                ],
                difficulty="easy",
                tags=["hindi", "number-place-value"],
            ),
            q(
                "q-math-5",
                [
                    txt(
                        "A right-angled triangle has the measurements below. What is the "
                        "length of the hypotenuse?"
                    ),
                    table(
                        [
                            ["Measurement", "Value"],
                            ["Short leg (a)", "5 cm"],
                            ["Other leg (b)", "12 cm"],
                            ["Angle at A", "90°"],
                        ]
                    ),
                ],
                {"a": ["13 cm"], "b": ["15 cm"], "c": ["17 cm"], "d": ["19 cm"]},
                ["a"],
                [
                    txt(
                        "The diagram shows the right angle at A, which is what makes side "
                        "BC the hypotenuse:"
                    ),
                    img(
                        "right_triangle", "Triangle ABC with a right angle marked at A"
                    ),
                    txt("Pythagoras gives "),
                    math(M_PYTHAGORAS),
                    txt(" so the hypotenuse is √(25 + 144) = √169 = 13 cm."),
                ],
                difficulty="medium",
                marks=2,
                tags=["geometry", "formula", "table-question"],
            ),
            q(
                "q-math-6",
                [
                    txt("Differentiate "),
                    math(M_CUBIC),
                    txt(" and find every value of "),
                    math(M_DERIV),
                    txt(" that equals zero."),
                    table(
                        [
                            ["x", "f(x)"],
                            ["0", "0"],
                            ["1", "4"],
                            ["2", "2"],
                            ["3", "0"],
                        ]
                    ),
                ],
                {
                    "a": [txt("0 only")],
                    "b": [txt("1 only")],
                    "c": [txt("1 and 3")],
                    "d": [txt("2 only")],
                },
                ["c"],
                [
                    txt("Differentiating term by term gives "),
                    math(M_DERIV),
                    txt(", which factors as "),
                    math(M_FACTOR),
                    txt(
                        ". It vanishes at x = 1 and x = 3, and the table agrees: f(1) = 4 "
                        "and f(3) = 0 are the turning points."
                    ),
                ],
                difficulty="hard",
                marks=3,
                tags=["calculus", "formula", "table-question", "multi-correct"],
            ),
            q(
                "q-math-7",
                [
                    txt(
                        "In how many ways can five people be seated in a row if two of "
                        "them insist on sitting together?"
                    )
                ],
                {
                    "a": ["24"],
                    "b": ["48"],
                    "c": ["96"],
                    "d": ["120"],
                },
                ["b"],
                [
                    txt(
                        "Treat the pair as one block: the block plus the other three "
                        "people is 4 units, giving "
                    ),
                    math("<mn>4</mn><mo>!</mo><mo>=</mo><mn>24</mn>"),
                    txt(
                        " arrangements, and the two inside the block can swap, giving "
                    ),
                    math("<mn>24</mn><mo>&#x00D7;</mo><mn>2</mn><mo>=</mo><mn>48</mn>"),
                    txt(
                        ". Ignoring the constraint would give 5! = 120, which is option "
                        "(d)."
                    ),
                ],
                difficulty="hard",
                marks=2,
                tags=["combinatorics", "formula"],
            ),
            q(
                "q-math-8",
                [
                    txt("Find the sum of the first 20 terms of "),
                    math("<mn>3</mn><mo>+</mo><mn>7</mn><mo>+</mo><mn>11</mn><mo>+</mo><mo>&#x22EF;</mo>"),
                    txt(" using "),
                    math(M_AP_SUM),
                    txt("."),
                    table(
                        [
                            ["n", "1", "2", "3", "20"],
                            ["Term", "3", "7", "11", "79"],
                        ]
                    ),
                ],
                {
                    "a": ["800"],
                    "b": ["820"],
                    "c": ["840"],
                    "d": ["860"],
                },
                ["b"],
                [
                    txt(
                        "Here a = 3 and d = 4, so the twentieth term is 3 + 19 × 4 = 79 and "
                        "the sum is "
                    ),
                    math(
                        "<mfrac><mi>n</mi><mn>2</mn></mfrac>"
                        "<mo>(</mo><mn>2</mn><mi>a</mi><mo>+</mo>"
                        "<mo>(</mo><mi>n</mi><mo>&#x2212;</mo><mn>1</mn><mo>)</mo>"
                        "<mi>d</mi><mo>)</mo>"
                    ),
                    txt(" = 10 × (6 + 76) = "),
                    math("<mn>820</mn>"),
                    txt("."),
                ],
                difficulty="hard",
                marks=2,
                tags=["series", "formula", "table-question"],
            ),
            q(
                "q-math-9",
                [
                    txt("A fair six-sided die is rolled twice. What is the probability "
                        "that the two numbers add up to 7?"),
                    table(
                        [
                            ["First die", "Second die"],
                            ["1", "6"],
                            ["2", "5"],
                            ["3", "4"],
                            ["4", "3"],
                            ["5", "2"],
                            ["6", "1"],
                        ]
                    ),
                ],
                {
                    "a": [txt("1/6")],
                    "b": [txt("1/12")],
                    "c": [txt("5/36")],
                    "d": [txt("1/3")],
                },
                ["a"],
                [
                    txt(
                        "There are 36 equally likely ordered outcomes and the table lists "
                        "the 6 that sum to 7, so "
                    ),
                    math(M_SEVEN),
                    txt(
                        ". Treating the sum as unordered would halve the count and give the "
                        "wrong 1/12."
                    ),
                ],
                difficulty="hard",
                marks=2,
                tags=["probability", "formula", "table-question"],
            ),
        ],
    },
    {
        "id": "cat-visual",
        "title": "Visual Reasoning",
        "questions": [
            q(
                "q-vis-1",
                [
                    txt(
                        "Which two of the four shapes are quadrilaterals? Look at each option "
                        "and tick every shape with exactly four straight sides."
                    )
                ],
                {
                    "a": [img("shape_rectangle", "A rectangle"), txt(" Rectangle")],
                    "b": [img("shape_square", "A square"), txt(" Square")],
                    "c": [
                        img("shape_triangle", "An equilateral triangle"),
                        txt(" Triangle"),
                    ],
                    "d": [
                        img("shape_pentagon", "A regular pentagon"),
                        txt(" Pentagon"),
                    ],
                },
                ["a", "b"],
                [
                    txt(
                        "A quadrilateral is any closed shape with four straight sides. The "
                        "rectangle and the square both qualify, the triangle has three sides "
                        "and the pentagon has five."
                    )
                ],
                difficulty="easy",
                tags=["shapes", "option-images", "multi-correct"],
            ),
            q(
                "q-vis-2",
                [
                    txt(
                        "The circle shows four sectors labelled A to D. Which two sectors are "
                        "equal in area?"
                    )
                ],
                {
                    "a": [txt("A and B")],
                    "b": [txt("B and C")],
                    "c": [txt("A and D")],
                    "d": [txt("C and D")],
                },
                ["b"],
                [
                    txt(
                        "The sectors run 35, 25, 25 and 15 per cent of the circle. B and C "
                        "are both 25 per cent, so they are exactly equal in area, while A is "
                        "the largest and D the smallest."
                    )
                ],
                difficulty="hard",
                marks=2,
                tags=["pie-chart", "image-question"],
            ),
            q(
                "q-vis-3",
                [
                    txt("Which number is marked on the number line?"),
                    img(
                        "number_line",
                        "A number line from 0 to 10 with a marker between 3 and 4",
                    ),
                ],
                {
                    "a": [txt("3")],
                    "b": [txt("3.5")],
                    "c": [txt("4")],
                    "d": [txt("7.5")],
                },
                ["b"],
                [
                    txt(
                        "The marker sits exactly halfway between 3 and 4, so it reads 3.5. "
                        "It is well to the left of 7, so 7.5 is not a candidate."
                    )
                ],
                difficulty="easy",
                tags=["number-line", "image-question"],
            ),
        ],
    },
    {
        "id": "cat-history",
        "title": "History",
        "questions": [
            q(
                "q-his-1",
                [
                    txt(
                        "Match each ruler to the year their reign in India began. Which two "
                        "matches are correct?"
                    )
                ],
                {
                    "a": [
                        table([["Ruler", "Began reign"], ["Akbar", "1556"]])
                    ],
                    "b": [
                        table([["Ruler", "Began reign"], ["Aurangzeb", "1707"]])
                    ],
                    "c": [
                        table([["Ruler", "Began reign"], ["Babur", "1526"]])
                    ],
                    "d": [
                        table([["Ruler", "Began reign"], ["Humayun", "1600"]])
                    ],
                },
                ["a", "c"],
                [
                    txt(
                        "Akbar acceded in 1556 after Humayun's death, and Babur founded "
                        "the empire at Panipat in 1526, so (a) and (c) are right. "
                    ),
                    txt(
                        "Aurangzeb took the throne in 1658 and died in 1707, so (b) gives "
                        "his death, not his accession. Humayun ruled from 1540 and was "
                        "restored in 1555, never in 1600, so (d) is wrong too."
                    ),
                ],
                difficulty="hard",
                marks=2,
                tags=["mughal", "table-question", "multi-correct"],
            ),
            q(
                "q-his-2",
                [
                    txt("भारत में अंग्रेज़ों का स्थापत्य काल कौन-सा था?"),
                    txt("(Which period saw British ascendancy in India?)"),
                ],
                {
                    "a": [txt("1858 से 1947 तक")],
                    "b": [txt("1600 से 1761 तक")],
                    "c": [txt("1947 से 1999 तक")],
                    "d": [txt("1757 से 1857 तक")],
                },
                ["a"],
                [
                    txt(
                        "1858 में भारत पर क्राउन नियंत्रण स्थापित हुआ और यह 1947 तक रहा। "
                        "1757 से 1857 का समय केवल कंपनी शासन का था।"
                    )
                ],
                difficulty="medium",
                tags=["hindi", "colonial"],
            ),
            q(
                "q-his-3",
                [
                    txt(
                        "Which ship carried the Titanic on its maiden voyage, and in which year "
                        "did it sink?"
                    )
                ],
                {
                    "a": ["RMS Olympic, 1912"],
                    "b": ["RMS Titanic, 1912"],
                    "c": ["RMS Britannic, 1915"],
                    "d": ["RMS Titanic, 1898"],
                },
                ["b"],
                [
                    txt(
                        "RMS Titanic struck an iceberg on 15 April 1912 on her maiden voyage. "
                        "Britannic sank in 1916 during the First World War, and Olympic never "
                        "sank at all."
                    ),
                ],
                difficulty="easy",
                tags=["general-knowledge", "legacy-fields"],
                # The older scalar question-picture field rather than an element.
                image=IMG["ship"],
            ),
        ],
    },
    {
        "id": "cat-geography",
        "title": "Geography",
        "questions": [
            q(
                "q-geo-1",
                [
                    txt("The table lists four dams. Which two stand on the same river?"),
                    table(
                        [
                            ["Dam", "River"],
                            ["Bhakra", "Sutlej"],
                            ["Nathpa-Jakhal", "Sutlej"],
                            ["Hirakud", "Mahanadi"],
                            ["Nagarjuna Sagar", "Krishna"],
                        ]
                    ),
                ],
                {
                    "a": [txt("Bhakra and Nathpa-Jakhal")],
                    "b": [txt("Bhakra and Hirakud")],
                    "c": [txt("Hirakud and Nagarjuna Sagar")],
                    "d": [txt("Nagarjuna Sagar and Nathpa-Jakhal")],
                },
                ["a"],
                [
                    txt(
                        "Read the River column: Bhakra and Nathpa-Jakhal are both on the "
                        "Sutlej near Bilaspur, the barrage sitting just downstream of the "
                        "dam. Hirakud is on the Mahanadi and Nagarjuna Sagar on the Krishna, "
                        "so no other pair matches."
                    )
                ],
                difficulty="medium",
                tags=["dams", "table-question"],
            ),
            q(
                "q-geo-2",
                [
                    txt("भारत की सबसे लंबी नदी कौन-सी है?"),
                    txt("(Which is the longest river in India?)"),
                ],
                {
                    "a": [txt("गंगा")],
                    "b": [txt("ब्रह्मपुत्र")],
                    "c": [txt("गोदावरी")],
                    "d": [txt("महानदी")],
                },
                ["a"],
                [
                    txt(
                        "गंगा की लंबाई लगभग 2525 किलोमीटर है, जो भारत की किसी भी अन्य "
                        "नदी से अधिक है, इसलिए यही सही उत्तर है।"
                    )
                ],
                difficulty="medium",
                tags=["hindi", "rivers"],
            ),
        ],
    },
    {
        # Uses the legacy `name` alias on purpose, so the importer's alias
        # handling stays covered by an asset that ships to users.
        "id": "cat-sports",
        "name": "Sports",
        "questions": [
            q(
                "q-spt-1",
                [
                    txt(
                        "How many players from one team are on the field in a standard "
                        "association football match?"
                    )
                ],
                {"a": ["9"], "b": ["10"], "c": ["11"], "d": ["12"]},
                ["c"],
                [
                    txt(
                        "Each side fields eleven players, one of whom is the goalkeeper, with "
                        "no substitutes counted until the match is under way."
                    )
                ],
                difficulty="easy",
                tags=["football"],
            ),
            q(
                "q-spt-2",
                [
                    txt(
                        "Which resting heart rate would most likely belong to a trained "
                        "endurance athlete?"
                    ),
                    table(
                        [
                            ["Athlete", "Resting rate"],
                            ["A", "72 bpm"],
                            ["B", "50 bpm"],
                            ["C", "68 bpm"],
                            ["D", "65 bpm"],
                        ]
                    ),
                ],
                {"a": ["A"], "b": ["B"], "c": ["C"], "d": ["D"]},
                ["b"],
                [
                    txt(
                        "A larger stroke volume from endurance training lowers resting heart "
                        "rate, so the lowest resting rate in the table belongs to the trained "
                        "athlete."
                    )
                ],
                difficulty="medium",
                tags=["table-question", "fitness"],
            ),
        ],
    },
    {
        "id": "cat-multichoice",
        "title": "Multiple Correct Answers",
        "questions": [
            q(
                "q-mc-1",
                [txt("Select every prime number.")],
                {
                    "a": [txt("2")],
                    "b": [txt("9")],
                    "c": [txt("13")],
                    "d": [txt("21")],
                    "e": [txt("1")],
                },
                ["a", "c"],
                [
                    txt(
                        "A prime has exactly two positive factors. 2 and 13 qualify; 9 = 3², "
                        "21 = 3 × 7 and 1 has a single factor."
                    )
                ],
                difficulty="easy",
                tags=["multi-correct", "number-theory"],
            ),
            q(
                "q-mc-2",
                [txt("Which three are physical quantities, rather than units?")],
                {
                    "a": [txt("Length")],
                    "b": [txt("Metre")],
                    "c": [txt("Force")],
                    "d": [txt("Kilogram")],
                    "e": [txt("Time")],
                },
                ["a", "c", "e"],
                [
                    txt(
                        "Length, force and time are quantities; metre and kilogram are the "
                        "SI units used to measure other quantities."
                    )
                ],
                difficulty="easy",
                tags=["multi-correct", "measurement"],
            ),
            q(
                "q-mc-3",
                [
                    txt(
                        "A body is thrown straight up. Which statements are true while it is "
                        "in the air, ignoring air resistance?"
                    )
                ],
                {
                    "a": [txt("The acceleration is constant and downward.")],
                    "b": [txt("The speed decreases all the way to the ground.")],
                    "c": [txt("The velocity reverses direction at the top.")],
                    "d": [txt("The acceleration is zero at the top.")],
                },
                ["a", "c"],
                [
                    math(M_GRAVITY),
                    txt(
                        " The acceleration stays constant and downward throughout, so it is "
                        "certainly not zero at the top, and the speed falls to zero then rises "
                        "again. Only (a) and (c) hold."
                    ),
                ],
                difficulty="hard",
                marks=2,
                tags=["multi-correct", "kinematics", "formula"],
            ),
        ],
    },
    {
        "id": "cat-bilingual",
        "title": "Bilingual — English + हिन्दी",
        "questions": [
            q(
                "q-bi-1",
                [
                    txt("What is the capital of Japan? / जापान की राजधानी क्या है?"),
                    txt(
                        "Choose one option only — the two languages describe the same question."
                    ),
                ],
                {
                    "a": [txt("Tokyo / टोक्यो")],
                    "b": [txt("Osaka / ओसाका")],
                    "c": [txt("Kyoto / क्योटो")],
                    "d": [txt("Nagoya / नागोया")],
                },
                ["a"],
                [
                    txt(
                        "Tokyo has been the seat of government since 1868, when the capital was "
                        "moved from Kyoto."
                    )
                ],
                difficulty="easy",
                tags=["bilingual", "japan"],
            ),
            q(
                "q-bi-2",
                [
                    txt(
                        "The table gives the composition of the human body by mass. Which two "
                        "entries are largest?"
                    ),
                    txt("मानव शरीर का द्रव्यमान-अनुसार संघटन नीचे दिया गया है।"),
                    table(
                        [
                            ["Component", "Share of body mass"],
                            ["Water", "60%"],
                            ["Protein", "16%"],
                            ["Fat", "12%"],
                            ["Mineral salts", "6%"],
                            ["Carbohydrate", "1%"],
                        ]
                    ),
                ],
                {
                    "a": [txt("Water and Protein / जल और प्रोटीन")],
                    "b": [txt("Protein and Fat / प्रोटीन और वसा")],
                    "c": [txt("Fat and Mineral salts / वसा और खनिज लवण")],
                    "d": [
                        txt("Mineral salts and Carbohydrate / खनिज लवण और कार्बोहाइड्रेट")
                    ],
                },
                ["a"],
                [
                    txt(
                        "Water at 60 per cent is the largest single component and protein at "
                        "16 per cent is next, so the pair is (a). वही क्रम तालिका से सीधे "
                        "पढ़ा जा सकता है।"
                    )
                ],
                difficulty="medium",
                tags=["bilingual", "table-question", "hindi"],
            ),
            q(
                "q-bi-3",
                [
                    txt(
                        "भारत का संविधान कब लागू हुआ? / When did the Constitution of India come "
                        "into force?"
                    ),
                    txt("26 January 1950 is the date to compare against."),
                ],
                {
                    "a": [txt("15 August 1947")],
                    "b": [txt("26 January 1950")],
                    "c": [txt("26 November 1949")],
                    "d": [txt("2 October 1950")],
                },
                ["b"],
                [
                    txt(
                        "The Constitution was adopted on 26 November 1949 but came into force "
                        "on 26 January 1950, when India became a republic."
                    )
                ],
                difficulty="easy",
                tags=["bilingual", "hindi", "constitution"],
            ),
        ],
    },
    {
        "id": "cat-order",
        "title": "Order Check and Marks",
        "questions": [
            q(
                "q-o1",
                [
                    txt(
                        "These steps are listed out of order. Which option gives the correct "
                        "sequence for preparing a sample slide?"
                    ),
                    table(
                        [
                            ["Step", "Action"],
                            ["1", "Fix the tissue"],
                            ["2", "Cut thin sections"],
                            ["3", "Stain the section"],
                            ["4", "Mount on a slide"],
                        ]
                    ),
                ],
                {
                    "a": [txt("2, 1, 4, 3")],
                    "b": [txt("1, 2, 4, 3")],
                    "c": [txt("4, 3, 2, 1")],
                    "d": [txt("1, 4, 2, 3")],
                },
                ["b"],
                [
                    txt(
                        "Fix first, then cut thin sections, mount those on a slide and stain "
                        "last, giving 1, 2, 4, 3."
                    )
                ],
                difficulty="medium",
                marks=2,
                tags=["ordering"],
            ),
            q(
                "q-o2",
                [
                    txt(
                        "Rank these states of matter from lowest to highest particle "
                        "attraction."
                    )
                ],
                {
                    "a": [txt("Gas, Liquid, Solid")],
                    "b": [txt("Solid, Liquid, Gas")],
                    "c": [txt("Liquid, Gas, Solid")],
                    "d": [txt("Gas, Solid, Liquid")],
                },
                ["a"],
                [
                    txt(
                        "Attraction is weakest in a gas, intermediate in a liquid and "
                        "strongest in a solid, which is why solids keep a fixed shape."
                    )
                ],
                difficulty="easy",
                marks=1,
                tags=["ordering"],
            ),
            q(
                "q-o3",
                [
                    txt(
                        "Rank these planets by their average distance from the Sun, nearest "
                        "first."
                    )
                ],
                {
                    "a": [txt("Mercury, Venus, Earth, Mars")],
                    "b": [txt("Venus, Mercury, Earth, Mars")],
                    "c": [txt("Earth, Mars, Venus, Mercury")],
                    "d": [txt("Mars, Earth, Venus, Mercury")],
                },
                ["a"],
                [
                    math(M_KEPLER),
                    txt(
                        " tells us that a planet's orbital period grows with its distance "
                        "from the Sun, and the mean distances rise in the same order: Mercury "
                        "57.9, Venus 108.2, Earth 149.6 and Mars 227.9 million kilometres."
                    ),
                ],
                difficulty="hard",
                marks=3,
                tags=["ordering", "formula"],
            ),
            q(
                "q-o4",
                [
                    txt(
                        "Which three practices make a numerical answer trustworthy?"
                    )
                ],
                {
                    "a": [txt("Writing the units")],
                    "b": [txt("Checking significant figures")],
                    "c": [txt("Guessing first")],
                    "d": [txt("Rounding early")],
                    "e": [txt("Stating the assumptions")],
                },
                ["a", "b", "e"],
                [
                    txt(
                        "Units, significant figures and stated assumptions all have to be in "
                        "place before the number means anything. Guessing sets no basis at all, "
                        "and rounding early discards precision."
                    )
                ],
                difficulty="medium",
                tags=["multi-correct", "measurement"],
            ),
        ],
    },
    {
        "id": "cat-edge",
        "title": "Edge Cases",
        "questions": [
            # No answer key: imports ungraded and the preview says so.
            q(
                "q-e1",
                [
                    txt(
                        "This question has no answer key on purpose, so it imports ungraded "
                        "and never affects a score."
                    )
                ],
                {"a": ["True"], "b": ["False"]},
                [],
                [
                    txt(
                        "A question with no correct option is still stored and shown, it just "
                        "cannot be scored."
                    )
                ],
                difficulty="easy",
                tags=["edge-case", "ungraded"],
            ),
            # No options: cannot be answered.
            q(
                "q-e2",
                [
                    txt(
                        "This question has no options at all, which the import preview flags "
                        "as unanswerable."
                    )
                ],
                {},
                [],
                [
                    txt(
                        "An empty option list is legal in the file format; the editor will ask "
                        "for options before the question can be saved from there."
                    )
                ],
                difficulty="easy",
                tags=["edge-case", "no-options"],
            ),
            # No explanation: nothing shows under the hint icon in Browse.
            q(
                "q-e3",
                [txt("This question deliberately has no explanation.")],
                {"a": ["First"], "b": ["Second"]},
                ["a"],
                [],
                difficulty="easy",
                tags=["edge-case", "no-explanation"],
            ),
            # No id: the importer derives a stable one from the content.
            # Legacy scalar fields: no id, and the older `image` /
            # `options[].image` / `explanationImage` shapes rather than
            # structured elements. Still supported, still worth demonstrating.
            # Deliberately no picture of its own, so the .apkg round-trip test
            # has a question whose option images must stay on their options.
            {
                "question": "This question has no id and uses the older scalar fields for "
                "its pictures, so the importer derives a stable id from its content.",
                "options": [
                    {"id": "a", "text": "Scalar field", "image": IMG["shape_square"]},
                    {"id": "b", "text": "Content element"},
                ],
                "correctIndex": 0,
                "explanation": "Structured elements are the modern shape; the scalar "
                "fields still import and still render.",
                "explanationImage": IMG["shape_triangle"],
                "difficulty": "easy",
                "tags": ["edge-case", "generated-id", "legacy-fields"],
            },
            # A single option cannot be answered either.
            q(
                "q-e5",
                [
                    txt(
                        "This question has only one option and no explanation, so the preview "
                        "flags both."
                    )
                ],
                {"a": ["Only choice"]},
                ["a"],
                [],
                difficulty="easy",
                tags=["edge-case", "one-option"],
            ),
        ],
    },
]


# Tags MathJax's MathML input accepts. Anything else draws a bare
# "Math input error" in the app, which says nothing about the cause, so a
# malformed formula has to be caught at generation time instead.
MATHML_TAGS = {
    "math",
    "mrow",
    "mi",
    "mn",
    "mo",
    "mtext",
    "mspace",
    "ms",
    "mlabeledtr",
    "msub",
    "msup",
    "msubsup",
    "mfrac",
    "msqrt",
    "mroot",
    "mstyle",
    "mpadded",
    "mphantom",
    "menclose",
    "mfenced",
    "mtable",
    "mtr",
    "mtd",
    "munder",
    "mover",
    "munderover",
    "merror",
    "semantics",
    "annotation",
    "maction",
}


def check_mathml(doc):
    """Every `math` element: known tags, and a real <math> wrapper.

    A string-replace over markup once turned every <mi> into <ai>, which
    rendered as "Math input error" with no clue as to why.
    """
    bad = []

    def walk(node, where):
        if isinstance(node, dict):
            if node.get("type") == "math":
                body = node.get("content", "")
                if "<math" not in body:
                    bad.append(f"{where}: no <math> wrapper")
                for tag in sorted(set(re.findall(r"</?([a-zA-Z][a-zA-Z0-9]*)", body))):
                    if tag not in MATHML_TAGS:
                        bad.append(f"{where}: unknown MathML tag <{tag}>")
            for key, value in node.items():
                walk(value, f"{where}/{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, f"{where}[{index}]")

    walk(doc, "")
    return bad


def build():
    papers = [
        {
            "id": "paper-demo",
            "title": "Sample Bank — English + हिन्दी",
            "description": "A showcase bank: MathML formulas and chemical equations, tables "
            "and pictures in the question, the options and the explanation, "
            "single and multiple correct answers, English and Hindi, plus "
            "deliberate edge cases.",
            "durationMinutes": 20,
            "negativeMarking": 0.25,
            "categories": CATEGORIES,
            "questions": [
                {
                    "id": "q-top-1",
                    "question_elements": [
                        txt(
                            "This question sits at the top level of the file with no category, "
                            "so the importer files it under Uncategorized."
                        ),
                        txt("शीर्षक स्तर की प्रश्नावली।"),
                    ],
                    "options_elements": {
                        "a": [{"type": "text", "content": "Categorised"}],
                        "b": [{"type": "text", "content": "Uncategorized / अवर्गीकृत"}],
                    },
                    "correctOptionIds": ["b"],
                    "explanation_elements": [
                        {
                            "type": "text",
                            "content": "Top-level questions are kept rather than "
                            "dropped, which is what this question "
                            "demonstrates.",
                        },
                        img(
                            "pie_sectors",
                            "A pie chart divided into four labelled sectors",
                        ),
                    ],
                    "difficulty": "easy",
                    "marks": 1,
                    "tags": ["edge-case", "uncategorized", "hindi"],
                },
            ],
        }
    ]
    return {"version": 1, "papers": papers}


def main():
    doc = build()
    bad = check_mathml(doc)
    if bad:
        raise SystemExit("invalid MathML in the sample bank:\n  " + "\n  ".join(bad))
    text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(text)
    questions = sum(len(c.get("questions", [])) for c in CATEGORIES) + 1
    print(
        "wrote %s (%d bytes, %d questions, %d categories)"
        % (os.path.normpath(OUT), len(text.encode("utf-8")), questions, len(CATEGORIES))
    )


if __name__ == "__main__":
    main()
