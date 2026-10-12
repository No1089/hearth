#!/usr/bin/env python3
"""Print to a GB01/GB02/GB03 "cat printer" over BLE — no vendor app.

Protocol (community reverse-engineered): every command is
    51 78 <cmd> 00 <len> 00 <data…> <crc8(data)> ff
written to characteristic ae01; the printer reports flow control on ae02
(…ae… 10 = pause, …ae… 00 = resume). Image rows are 384 px = 48 bytes,
LSB-first, 1 = black.

    python3 catprint.py --test                 # test receipt
    python3 catprint.py --text "Milk\nBread"   # plain lines
    python3 catprint.py --image receipt.png
"""
import argparse, asyncio, sys
from datetime import datetime
from bleak import BleakClient, BleakScanner
from PIL import Image, ImageDraw, ImageFont, ImageOps

WIDTH = 384
TX, RX = '0000ae01-0000-1000-8000-00805f9b34fb', '0000ae02-0000-1000-8000-00805f9b34fb'
NAMES = ('GB01', 'GB02', 'GB03', 'MX05', 'MX06', 'MX08', 'MX10')

def _crc8_table():
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
        t.append(c)
    return t
CRC = _crc8_table()

def cmd(code, data):
    data = bytes(data)
    crc = 0
    for b in data:
        crc = CRC[crc ^ b]
    return bytes([0x51, 0x78, code, 0x00, len(data), 0x00]) + data + bytes([crc, 0xFF])

GET_STATE   = cmd(0xA3, [0x00])
QUALITY     = cmd(0xA4, [0x33])
LATTICE_ON  = cmd(0xA6, [0xAA, 0x55, 0x17, 0x38, 0x44, 0x5F, 0x5F, 0x5F, 0x44, 0x38, 0x2C])
LATTICE_OFF = cmd(0xA6, [0xAA, 0x55, 0x17, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x17])
APPLY_ENERGY = cmd(0xBE, [0x01])
UPDATE      = cmd(0xA9, [0x00])
def energy(e): return cmd(0xAF, [e & 0xFF, (e >> 8) & 0xFF])
def feed(n):   return cmd(0xA1, [n & 0xFF, (n >> 8) & 0xFF])
def speed(v):  return cmd(0xBD, [v & 0xFF])          # lower = slower = darker

def row_bytes(px):  # px: 384 ints (1 = black) → 48 bytes, LSB = leftmost
    out = bytearray(WIDTH // 8)
    for x, v in enumerate(px):
        if v:
            out[x >> 3] |= 1 << (x & 7)
    return bytes(out)

def to_rows(img):
    img = img.convert('L')
    if img.width != WIDTH:
        img = img.resize((WIDTH, round(img.height * WIDTH / img.width)))
    img = img.convert('1')                       # Floyd–Steinberg dither
    w, h = img.size
    p = img.load()
    return [row_bytes([1 if p[x, y] == 0 else 0 for x in range(w)]) for y in range(h)]

def job(img, e=0xFFFF, spd=None):
    out = bytearray(GET_STATE + QUALITY + energy(e) + APPLY_ENERGY + UPDATE + LATTICE_ON)
    if spd:
        out += speed(spd)
    for r in to_rows(img):
        out += cmd(0xA2, r)
    out += feed(90) + LATTICE_OFF + GET_STATE
    return bytes(out)

def font(size, bold=False):
    for f in (('/System/Library/Fonts/Supplemental/Arial Bold.ttf' if bold else '/System/Library/Fonts/Supplemental/Arial.ttf'),
              '/System/Library/Fonts/Helvetica.ttc', '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()

def render_lines(lines, title=None):
    h = 40 + (60 if title else 0) + 40 * len(lines)
    img = Image.new('L', (WIDTH, h), 255)
    d = ImageDraw.Draw(img)
    y = 10
    if title:
        d.text((8, y), title, font=font(38, True), fill=0); y += 52
        d.line((8, y, WIDTH - 8, y), fill=0, width=3); y += 12
    for ln in lines:
        d.text((8, y), ln, font=font(30), fill=0); y += 40
    return img

def test_image():
    img = render_lines(['☐ Milk', '☐ Bread', '☐ Tamatie', '',
                        datetime.now().strftime('%a %d %b %H:%M')], title='Kitchen printer ✓')
    bar = Image.linear_gradient('L').rotate(90).resize((WIDTH, 40))   # shows the grey/dither range
    out = Image.new('L', (WIDTH, img.height + 50), 255)
    out.paste(img, (0, 0)); out.paste(ImageOps.invert(bar), (0, img.height + 5))
    return out

async def send(data, name=None):
    dev = await BleakScanner.find_device_by_filter(
        lambda d, a: (d.name or a.local_name or '') in ((name,) if name else NAMES), timeout=15)
    if not dev:
        sys.exit('Printer not found — is it on?')
    paused = asyncio.Event(); paused.set()
    def on_rx(_, b):
        if len(b) >= 7 and b[2] == 0xAE:            # flow control
            (paused.clear if b[6] == 0x10 else paused.set)()
    async with BleakClient(dev) as c:
        await c.start_notify(RX, on_rx)
        chunk = max(20, min(180, (c.mtu_size or 23) - 3))
        for i in range(0, len(data), chunk):
            await paused.wait()
            await c.write_gatt_char(TX, data[i:i + chunk], response=False)
            await asyncio.sleep(0.015)
        await asyncio.sleep(2)                      # let it finish feeding before we drop the link
    print(f'sent {len(data)} bytes to {dev.name}')

async def keepalive(minutes, name=None):
    """Does holding a connection (and pinging) stop the printer's auto power-off?"""
    import time
    dev = await BleakScanner.find_device_by_filter(
        lambda d, a: (d.name or a.local_name or '') in ((name,) if name else NAMES), timeout=15)
    if not dev:
        sys.exit('Printer not found — is it on?')
    t0 = time.time()
    def on_rx(_, b):
        print(f'{(time.time() - t0) / 60:5.1f} min  printer says {b.hex()}', flush=True)
    async with BleakClient(dev, disconnected_callback=lambda c: print(
            f'{(time.time() - t0) / 60:5.1f} min  DISCONNECTED (printer switched off?)', flush=True)) as c:
        await c.start_notify(RX, on_rx)
        while time.time() - t0 < minutes * 60 and c.is_connected:
            await c.write_gatt_char(TX, GET_STATE, response=False)
            await asyncio.sleep(20)
    print(f'done after {(time.time() - t0) / 60:.1f} min')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--test', action='store_true'); ap.add_argument('--text'); ap.add_argument('--image')
    ap.add_argument('--energy', type=lambda s: int(s, 0), default=0xFFFF); ap.add_argument('--name')
    ap.add_argument('--speed', type=lambda s: int(s, 0), help='e.g. 0x10 (slow, dark) … 0x30')
    ap.add_argument('--keepalive', type=float, metavar='MIN', help='stay connected and ping every 20 s for MIN minutes')
    a = ap.parse_args()
    if a.keepalive:
        asyncio.run(keepalive(a.keepalive, a.name)); return
    if a.image: img = Image.open(a.image)
    elif a.text: img = render_lines(a.text.replace('\\n', '\n').split('\n'))
    else: img = test_image()
    asyncio.run(send(job(img, a.energy, a.speed), a.name))

if __name__ == '__main__':
    main()
