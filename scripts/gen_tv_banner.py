#!/usr/bin/env python3
"""Generates the Android TV banner (320x180 PNG, xhdpi) without PIL.

Run from the repository root:
    python3 scripts/gen_tv_banner.py app/src/main/res/drawable-xhdpi/tv_banner.png
"""
import struct
import sys
import zlib

W, H = 320, 180

# 5x7 pixel font (subset) for the banner text.
FONT = {
    'I': ["01110", "00100", "00100", "00100", "00100", "00100", "01110"],
    'P': ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    '-': ["00000", "00000", "00000", "01110", "00000", "00000", "00000"],
    'T': ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    'V': ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    'L': ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    'A': ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    'Y': ["10001", "10001", "01110", "00100", "00100", "00100", "00100"],
    'E': ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    'R': ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    ' ': ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}

BG_TOP = (10, 18, 30)
BG_BOTTOM = (19, 47, 62)
CYAN = (34, 211, 238)
WHITE = (232, 241, 248)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def build_pixels():
    px = [[BG_TOP for _ in range(W)] for _ in range(H)]
    for y in range(H):
        for x in range(W):
            px[y][x] = lerp(BG_TOP, BG_BOTTOM, y / (H - 1))

    def put(x, y, c):
        if 0 <= x < W and 0 <= y < H:
            px[y][x] = c

    # Play triangle, x 36..92, y 62..118
    x0, x1 = 36, 92
    y0, y1 = 62, 118
    for yy in range(y0, y1 + 1):
        t = (yy - y0) / (y1 - y0)
        half = (x1 - x0) / 2 * t
        cx = (x0 + x1) / 2
        for xx in range(int(cx - half), int(cx + half) + 1):
            put(xx, yy, CYAN)

    # Text "IP-TV PLAYER"
    text = "IP-TV PLAYER"
    scale = 3
    tx, ty = 108, 90
    for ch in text:
        glyph = FONT.get(ch)
        if glyph:
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == '1':
                        for sy in range(scale):
                            for sx in range(scale):
                                put(tx + gx * scale + sx, ty + gy * scale + sy, WHITE)
        tx += (5 * scale) + scale
    return px


def png_chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data +
            struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))


def write_png(path, px):
    raw = b''.join(b'\x00' + bytes(v for c in row for v in c) for row in px)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(png_chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)))
        f.write(png_chunk(b'IDAT', zlib.compress(raw, 9)))
        f.write(png_chunk(b'IEND', b''))


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else 'tv_banner.png'
    write_png(out, build_pixels())
    print(f'written {out} ({W}x{H})')
