# busy-agent (Windows)

The Windows port of [mac/busy-agent](../mac/README.md): one PowerShell script
that tells Hearth when this PC is on a call, so the kitchen tablet can show
the banner. Same rules, same protocol: a camera counts at once, a microphone
after 8 s, a report every 30 s, stale after 90 s.

## How it detects a call

Windows keeps, for every app that has ever used the camera or the microphone,
a `LastUsedTimeStart` and a `LastUsedTimeStop` in the registry under
`CapabilityAccessManager\ConsentStore\webcam` and `\microphone`, in both HKCU
and HKLM. `LastUsedTimeStop` is 0 while the app has the device open. That is
what the privacy indicator in the taskbar reads, so what it shows is what the
agent sees. Packaged apps are subkeys; desktop apps are under `NonPackaged`,
named by their path with `#` for `\`.

Windows doesn't record *which* camera, so the report says `camera` and
`microphone` rather than naming a device, and `-Ignore` takes app keys: a
virtual camera that is always "in use" is ignored with
`-Ignore 'C:#Program Files#OBS Studio#bin#64bit#obs64.exe'`. `-Once` prints the
keys it found, which is where to get the exact spelling.

Nothing is captured; the script reads two numbers per app.

## Install

In a PowerShell window:

```powershell
.\busy-agent.ps1 -Install https://hearth.example.com      # a Scheduled Task, hidden, at logon
.\busy-agent.ps1 -Install https://hearth.example.com -Ignore 'C:#Program Files#OBS Studio#bin#64bit#obs64.exe'
.\busy-agent.ps1 -Uninstall
```

If scripts are blocked, `powershell -ExecutionPolicy Bypass -File .\busy-agent.ps1 ...`.
The task copies the script to `%LOCALAPPDATA%\hearth\` and logs to
`busy-agent.log` there: one line per state change, and one when reports start
or stop getting through.

Run it by hand to see what it sees:

```powershell
.\busy-agent.ps1 https://hearth.example.com -Once
# camera apps: [Microsoft.Teams_8wekyb3d8bbwe], microphone apps: [] -> busy=True
```

## Status

Written to the same contract as the Mac agent and to the registry keys the
Windows privacy indicator uses. It parses (PowerShell 7.4), but has not been
run on Windows. The first real run is the test; `-Once` during a call is the
way to do it.

`-Once` lists a microphone it sees but still says `busy=False`: the 8 s
grace needs the loop. To check a microphone on its own, run it without
`-Once` for ten seconds.
