# busy-agent (macOS)

A 130-line Swift program that tells Hearth when this Mac is on a call, so the
kitchen tablet can show the kids a red **"Dad's on a call"** banner.

## Why it exists

The Home Assistant companion app for macOS has "camera in use" and "microphone
in use" sensors, and the banner was originally meant to read those. On macOS
26 they report `unavailable` whatever the camera is doing: the app sends
updates, Home Assistant accepts them, and the entities never change. Reloading
the integration brings them to life for about fifteen seconds. Meanwhile the
operating system answers the question correctly for anyone who asks it, so
this asks it.

## How it detects a call

macOS keeps a flag per capture device, "is running somewhere", that is set
while *any* process has the device open. The agent polls it every two seconds:

- **Cameras** via Core Media I/O:
  `CMIOObjectGetPropertyData(device, kCMIODevicePropertyDeviceIsRunningSomewhere)`
- **Microphones** via Core Audio:
  `AudioObjectGetPropertyData(device, kAudioDevicePropertyDeviceIsRunningSomewhere)`,
  for devices that have at least one input stream.

Neither needs the camera or microphone permission. The agent never captures
anything; it reads one integer per device. It can't tell which app is using
the device, and it doesn't try.

Rules, chosen for a kitchen rather than a security product:

| | |
|---|---|
| Camera in use | busy immediately. FaceTime, Teams, Zoom, Photo Booth all count. |
| Microphone in use | busy after **8 s** of continuous use, so a few seconds of dictation doesn't send the kids away. |
| Device in `--ignore` | never counts (e.g. a virtual camera that is always "running"). |
| Nothing in use | free. |

## The contract with Hearth

On every change, and at least every **30 s** as a heartbeat:

```http
POST /api/busy
Content-Type: application/json

{"source": "Chris's Mac mini", "busy": true, "devices": ["Brio 500"]}
```

Hearth treats a source as busy only while its last report is under **90 s**
old. A Mac that goes to sleep mid-call, or loses the network, therefore clears
the banner within a minute and a half rather than leaving it up all evening.
Several sources may report; any one busy is busy.

That is the whole protocol, so an equivalent for another operating system is a
small script: find out whether a capture device is open, POST that every 30
seconds. There is one for each: [windows/](../windows/README.md) reads the
registry keys the privacy indicator uses, and [linux/](../linux/README.md)
reads PipeWire's running capture streams and open `/dev/video*` devices.

## Install

```sh
./install.sh https://hearth.example.com      # build, then run at login
./install.sh --uninstall
```

`install.sh` compiles `busy-agent.swift` with the system `swiftc` (Xcode
Command Line Tools), puts the binary in
`~/Library/Application Support/hearth/`, and registers a LaunchAgent
`dev.hearth.busy-agent` with `KeepAlive`, so it restarts if it ever crashes.
It logs to `~/Library/Logs/hearth-busy-agent.log`: one line per state change,
and a single line when reports start or stop getting through. If macOS asks
whether busy-agent may find devices on the local network, say yes.

Run it by hand to see what it sees:

```sh
~/Library/Application\ Support/hearth/busy-agent https://hearth.example.com --once
# cameras in use: ["Brio 500"], mics in use: [] → busy=true
```

## What it sends, and what it doesn't

Device names, a boolean, and the Mac's name. No audio, no video, no app names,
no window titles. It talks only to the Hearth URL it was installed with, over
HTTPS, on the home network.
