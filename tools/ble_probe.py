#!/usr/bin/env python3
"""Identify a cheap BLE receipt printer: scan, then dump the GATT services of the best match.

Run from Terminal.app (it can ask macOS for Bluetooth permission):
    python3 ble_probe.py            # scan + probe anything that looks like a printer
    python3 ble_probe.py <name|address>
"""
import asyncio, sys
from bleak import BleakClient, BleakScanner

HINTS = ('print', 'fine', 'mx', 'gb0', 'gt0', 'yt0', 'mtp', 'pt-', 'p5', 'cat', 'pos', 'bt', 'phomemo', 'peripage', 'paperang')

async def main():
    want = sys.argv[1].lower() if len(sys.argv) > 1 else None
    print('Scanning for 12 s… (keep the printer switched on and close by)')
    found = await BleakScanner.discover(timeout=12, return_adv=True)
    rows = sorted(found.values(), key=lambda x: -x[1].rssi)
    for d, adv in rows:
        name = d.name or adv.local_name or ''
        print(f'{adv.rssi:4} dBm  {d.address}  {name!r:28} svc={adv.service_uuids} '
              f'mfr={ {k: v.hex() for k, v in adv.manufacturer_data.items()} }')
    def match(d, adv):
        name = (d.name or adv.local_name or '').lower()
        if want:
            return want in name or want == d.address.lower()
        return name and any(h in name for h in HINTS)
    targets = [(d, a) for d, a in rows if match(d, a)]
    if not targets:
        print('\nNo printer-looking device found. Re-run with its name or address as an argument.')
        return
    for d, adv in targets[:3]:
        print(f'\n=== {d.name or adv.local_name} ({d.address}) ===')
        try:
            async with BleakClient(d, timeout=15) as c:
                for s in c.services:
                    print(f'service {s.uuid}  {s.description}')
                    for ch in s.characteristics:
                        print(f'   char {ch.uuid}  [{",".join(ch.properties)}]  {ch.description}')
        except Exception as e:
            print('  could not connect:', e)

asyncio.run(main())
