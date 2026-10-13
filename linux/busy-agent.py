#!/usr/bin/env python3
"""busy-agent for Linux: tells Hearth when this machine is on a call.

The same contract as mac/busy-agent: POST {source, busy, devices} to
<hearth>/api/busy on every change and every 30 s as a heartbeat. A camera in
use counts at once; a microphone after 8 s of continuous use, so a few seconds
of dictation don't send the kids away.

How it knows:

- PipeWire. `pw-dump` lists every node and link. A node whose media.class is
  Stream/Input/Audio or Stream/Input/Video and whose state is "running" is an
  app capturing; the link from it leads to the device, and the device's
  description ("Built-in Audio Analog Stereo", "Integrated Camera") is what
  gets reported. A stream linked only to a sink's monitor, which is how screen
  recorders capture desktop audio, is not a microphone and doesn't count.
- /dev/video*. Any process of this user with one open (visible in
  /proc/<pid>/fd) is using that camera. That catches apps that talk to V4L2
  directly rather than through PipeWire. The name is from /sys/class/video4linux.

    busy-agent.py https://hearth.example.com [--ignore "Dummy video device"]... [--once]
"""

import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

MIC_GRACE, POLL, HEARTBEAT = 8, 2, 30
# Already covered by the PipeWire check; their own open fds are not an app using the camera.
PIPEWIRE_DAEMONS = {'pipewire', 'wireplumber', 'pipewire-media-'}


def log(msg):
    print(datetime.now(timezone.utc).isoformat(timespec='seconds'), msg, flush=True)


def pw_dump():
    try:
        out = subprocess.run(['pw-dump'], capture_output=True, text=True, timeout=5).stdout
        return json.loads(out) if out.strip() else []
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return []


def pipewire_in_use(objs):
    """(cameras, microphones): the devices behind running capture streams."""
    nodes = {o['id']: o for o in objs if o.get('type') == 'PipeWire:Interface:Node'}
    links = [o.get('info') or {} for o in objs if o.get('type') == 'PipeWire:Interface:Link']
    cams, mics = set(), set()
    for node in nodes.values():
        info = node.get('info') or {}
        cls = (info.get('props') or {}).get('media.class', '')
        if not cls.startswith('Stream/Input/') or info.get('state') != 'running':
            continue
        want = 'Video/Source' if cls.startswith('Stream/Input/Video') else 'Audio/Source'
        for link in links:
            if link.get('input-node-id') != node['id']:
                continue
            src = (nodes.get(link.get('output-node-id')) or {}).get('info') or {}
            props = src.get('props') or {}
            if not props.get('media.class', '').startswith(want):
                continue                                # a sink's monitor, not a microphone
            name = props.get('node.description') or props.get('node.nick') or props.get('node.name')
            (cams if want == 'Video/Source' else mics).add(name or want.split('/')[0].lower())
    return cams, mics


def v4l2_in_use():
    """Cameras that a process of this user has open."""
    names = set()
    for pid in os.listdir('/proc'):
        if not pid.isdigit() or int(pid) == os.getpid():
            continue
        try:
            with open(f'/proc/{pid}/comm') as f:
                if f.read().strip() in PIPEWIRE_DAEMONS:
                    continue
            fds = os.listdir(f'/proc/{pid}/fd')
        except OSError:
            continue                                    # another user's process, or gone
        for fd in fds:
            try:
                target = os.readlink(f'/proc/{pid}/fd/{fd}')
            except OSError:
                continue
            if target.startswith('/dev/video'):
                dev = target.rsplit('/', 1)[-1]
                try:
                    with open(f'/sys/class/video4linux/{dev}/name') as f:
                        names.add(f.read().strip())
                except OSError:
                    names.add(dev)
    return names


def in_use(ignore):
    cams, mics = pipewire_in_use(pw_dump())
    cams |= v4l2_in_use()
    return sorted(cams - ignore), sorted(mics - ignore)


failing = False     # log failures once, not every heartbeat


def post(url, body):
    global failing
    req = urllib.request.Request(url, json.dumps(body).encode(), {'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            r.read()
        if failing:
            log('reports reaching Hearth again')
            failing = False
    except (urllib.error.URLError, OSError, ValueError) as e:
        if not failing:
            log(f'report failed: {e} (will keep trying quietly)')
            failing = True


def main(argv):
    ignore, once, rest = set(), False, []
    it = iter(argv)
    for a in it:
        if a == '--once':
            once = True
        elif a == '--ignore':
            ignore.add(next(it, ''))
        else:
            rest.append(a)
    if len(rest) != 1:
        sys.exit('usage: busy-agent.py <hearth url> [--ignore <device name>]... [--once]')
    url = rest[0].rstrip('/') + '/api/busy'
    source = socket.gethostname()

    mic_since, last_busy, last_sent = None, None, 0.0
    while True:
        cams, mics = in_use(ignore)
        mic_since = None if not mics else (mic_since or time.monotonic())
        mic_busy = mic_since is not None and time.monotonic() - mic_since >= MIC_GRACE
        busy = bool(cams) or mic_busy
        devices = cams + (mics if mic_busy else [])

        if once:
            print(f'cameras in use: {cams}, mics in use: {mics} → busy={busy}')
            post(url, {'source': source, 'busy': busy, 'devices': devices})
            return
        if busy != last_busy or time.monotonic() - last_sent >= HEARTBEAT:
            if busy != last_busy:
                log('BUSY (' + ', '.join(devices) + ')' if busy else 'free')
            post(url, {'source': source, 'busy': busy, 'devices': devices})
            last_busy, last_sent = busy, time.monotonic()
        time.sleep(POLL)


if __name__ == '__main__':
    main(sys.argv[1:])
