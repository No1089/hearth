# busy-agent (Linux)

The Linux port of [mac/busy-agent](../mac/README.md): one Python file with no
dependencies that tells Hearth when this machine is on a call, so the kitchen
tablet can show the banner. Same rules, same protocol: a camera counts at
once, a microphone after 8 s, a report every 30 s, stale after 90 s.

## How it detects a call

Two ways, unioned:

- **PipeWire.** `pw-dump` lists every node and link. A node of class
  `Stream/Input/Audio` or `Stream/Input/Video` in state `running` is an app
  capturing, and the link from it leads to the device. The device's
  description is what gets reported: "Built-in Audio Analog Stereo",
  "Integrated Camera". A stream linked only to a sink's monitor, which is how
  a screen recorder captures desktop audio, is not a microphone and doesn't
  count.
- **`/dev/video*`.** Any process of this user with a video device open, read
  from `/proc/<pid>/fd`, is using that camera. This catches apps that use V4L2
  directly rather than through PipeWire. The name comes from
  `/sys/class/video4linux`. PipeWire's own daemons are skipped, since the first
  check already covers them.

Neither reads any audio or video. It needs `pw-dump` (package `pipewire-utils`
on Fedora, `pipewire-bin` on Debian and Ubuntu); without it only the camera
check runs. A PulseAudio-only desktop is not covered.

## Install

```sh
./install.sh https://hearth.example.com                      # systemd user service, starts at login
./install.sh https://hearth.example.com --ignore "Dummy video device"
./install.sh --uninstall
journalctl --user -u hearth-busy-agent -f
```

Run it by hand to see what it sees:

```sh
python3 busy-agent.py https://hearth.example.com --once
# cameras in use: ['Integrated Camera'], mics in use: [] → busy=True
```

## Status

Written to the same contract as the Mac agent and to PipeWire's documented
object model. Run (2026-10-14) against PipeWire 1.4 with no desktop and a
virtual microphone: recording from it turned the banner on after 8 s and off
when it stopped, and recording a sink's monitor, the way a screen recorder
does, did not count. Not yet run with a camera, or on a desk during a real
call; `--once` during a call is the way to do that.

`--once` lists a microphone it sees but still says `busy=False`: the 8 s
grace needs the loop. To check a microphone on its own, run it without
`--once` for ten seconds.
