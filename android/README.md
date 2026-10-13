# Hearth kiosk (Android)

A small home-made replacement for Fully Kiosk Browser, built for a Samsung Galaxy Tab A11 8.7"
(1340×800, Android 15 / One UI) hanging in the kitchen and showing the Hearth web dashboard.

- Full-screen, immersive, landscape-locked WebView (JS, DOM storage, autoplay without gesture).
- Reconnect screen + retry with backoff (5 s → 60 s) on load errors / 5xx / renderer crashes; reloads
  the page once it is older than 24 h.
- Kiosk lock when provisioned as **device owner**: lock task, no keyguard, stay-on-while-plugged,
  persistent HOME activity, safe mode and extra users disallowed, camera/Bluetooth permissions
  auto-granted. Without device owner it's a normal app that can still be chosen as the launcher.
- Top-level navigation is pinned to the start URL's host: links to other sites, `tel:`, `mailto:`,
  `intent:` etc. are swallowed (logged); `/reload` always goes back to the start URL.
- **Dims instead of sleeping** (the camera stops when the screen really turns off): window brightness
  → 0 plus a black overlay. Touch, motion, `/wake`, or `Kiosk.wake()` wakes it. Auto-dim after N
  minutes (default 10) without motion/touch; optional night window with a lower awake brightness.
  The power button is the one way to black the screen out completely; a remote wake (`/wake` from
  a busy/occupancy automation, `Kiosk.wake()`) turns it back on, touch and motion can't (the camera
  is off then).
- Front-camera motion detection (CameraX `ImageAnalysis`, ~320×240, analysed at 2 fps, 32×24 block
  grid). The sensor itself is asked for its slowest supported AE frame-rate range (logged at bind)
  instead of streaming ~30 fps around the clock.
- Page integration: `kiosk` DOM events and a `window.Kiosk` bridge.
- Bluetooth LE printing to a GB03 "cat printer" (see below).
- Token-protected local HTTP API (default port 2323) and optional Home Assistant webhook push.
- Hidden settings: tap the top-left corner (80×80 dp) 5× within 3 s → PIN (default `1234`).

Nothing site-specific is compiled in. URL, token, webhook, PIN live in the app's preferences on
the device (set via Settings or the adb intent below).

## Toolchain (macOS, Apple Silicon, no Android Studio)

```sh
brew install openjdk@21
brew install --cask android-commandlinetools
# Gradle from brew was only used once to generate the wrapper, then uninstalled (it pulls in
# its own ~400 MB JDK). The wrapper (gradle/wrapper/) downloads Gradle 9.8.1 by itself.

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-35" "build-tools;36.0.0"
```

AGP 9.4 requires build-tools ≥ 36.0.0 (it ignores 35.0.0 and auto-installs 36.0.0 on first
build if missing). Disk: JDK ~330 MB, SDK ~530 MB, `~/.gradle` ~730 MB after the first build.

Versions: Gradle 9.8.1 (wrapper), AGP 9.4.1 with built-in Kotlin, compileSdk/targetSdk 35,
minSdk 29. AndroidX deps are pinned to the last releases that still compile against android-35
(core-ktx 1.16.0, activity 1.10.1, CameraX 1.4.2) — bumping them needs `platforms;android-36`.

## Build

```sh
cd android
./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk (needs keystore.properties, below)
./gradlew test              # JVM unit tests (motion detector, HTTP parsing, night window, cat-printer protocol)
```

`local.properties` (gitignored) is optional when `ANDROID_HOME` is set.

### Release signing

A device-owner app can only ever be updated by the key it was installed with: lose the keystore
and the only way to ship a new build is a factory reset (or `remove_owner`, below, then reinstall).
So: create the keystore once, **back it up** (password manager + offline copy), and never commit it.

```sh
cd android
keytool -genkeypair -v -keystore hearth-release.jks -alias hearth -keyalg RSA -keysize 4096 -validity 10000
cat > keystore.properties <<'EOF'
storeFile=hearth-release.jks
storePassword=...
keyAlias=hearth
keyPassword=...
EOF
./gradlew assembleRelease
```

`keystore.properties` and `*.jks` are gitignored; `storeFile` is relative to `android/`. Without
the properties file `assembleRelease` fails with a message saying so (it never produces an
unsigned APK by accident). Release builds are not debuggable; debug builds use the default debug
key and should only go on a tablet that was provisioned with a debug build.

### Releases

Pushing a tag `v*` runs `.github/workflows/android-release.yml`: unit tests, a debug build, and a
GitHub release with the APK attached. It signs a release build only when the keystore is in the
repository's secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`); until then a release carries the debug APK only. Whether the key that owns
the tablet belongs in GitHub is the question to answer first: anyone who can read those secrets can
ship an update the tablet will accept.

## Provisioning the tablet

1. **Protect battery** — it will be on the charger 24/7: Settings → Battery → (More battery settings) →
   Protect battery (85 % cap). Do this first; it survives as a normal setting.
2. **Factory reset** (Settings → General management → Reset → Factory data reset).
3. In the setup wizard **skip the Google account and the Samsung account**, connect to Wi-Fi.
   `dpm set-device-owner` refuses to run if *any* account exists on the device.
4. Enable developer options (Settings → About tablet → Software information → tap *Build number* 7×),
   then Developer options → **USB debugging** on. Connect USB, accept the RSA prompt.
5. Install the **release** build (signed with your own key, see *Release signing*) and provision.
   Whatever key the first install uses is the only key that can ever update the app afterwards.

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner dev.homekiosk/.AdminReceiver
adb shell appops set dev.homekiosk TURN_SCREEN_ON allow   # a remote wake can then turn a power-button-off display back on (Android 14+)

# Headless config (all optional except url/token). --es values are strings; --ei/--ez also work.
adb shell am start -n dev.homekiosk/.MainActivity \
    --es url "https://dash.example.com/" \
    --es token "$(openssl rand -hex 24)" \
    --es webhook "https://ha.example.com/api/webhook/kitchen-kiosk" \
    --es pin 4826
```

Other accepted extras: `port` (1024–65535), `brightness` (0–255), `night` (true/false),
`night_start`/`night_end` (`HH:MM`), `night_brightness`, `sleep_minutes`, `motion` (true/false),
`motion_debug` (true/false), `printer` (true/false). Values are validated — `url`/`webhook` must be
http(s) URLs, `pin` 4–8 digits, numbers in range — and an invalid one is ignored (logged, not
clamped). Extras are applied once and then dropped from the intent, so a recreated activity doesn't
re-apply a stale URL. One-shot actions (not saved): `--ez print_test true`,
`--ez remove_owner true --es pin <PIN>` (see *Undoing device owner*).

The first `am start` (or a reboot) applies the device-owner policies and enters lock task; if
`dpm set-device-owner` runs while the app is already in front, the next 15 s tick picks it up. Check:

```sh
adb shell dumpsys device_policy | grep -A3 "Device Owner"
adb shell dumpsys activity activities | grep mLockTaskModeState   # LOCKED
adb shell dumpsys user | grep -E "no_safe_boot|no_add_user"                                 # no_safe_boot, no_add_user
curl -s "http://192.0.2.10:2323/status?token=YOUR_TOKEN"
```

### Samsung / Knox caveats

- `java.lang.IllegalStateException: Not allowed to set the device owner because there are already some
  accounts on the device` → an account exists (Google, Samsung, sometimes a leftover from Galaxy
  Store/Samsung Pass). Remove them in Settings → Accounts, or factory-reset and skip all sign-ins.
- "...already several users on the device" → remove secondary users / Secure Folder.
- Some One UI builds create a Samsung account placeholder after the wizard; if `dpm` still fails with
  no visible accounts, check `adb shell dumpsys account` and factory-reset again.
- Lock task allows the power-button menu (LOCK_TASK_FEATURE_GLOBAL_ACTIONS) so you can still reboot;
  home, recents, notifications and the status bar are blocked. Safe mode (which would start the
  tablet without third-party apps, i.e. without the kiosk) is disallowed for the same reason.
- Samsung's "Adaptive brightness" and screen timeout no longer matter: the app keeps the screen on
  (FLAG_KEEP_SCREEN_ON + STAY_ON_WHILE_PLUGGED_IN) and overrides window brightness itself.
- If brightness 0 turns the backlight fully off or behaves oddly on this panel, raise
  *Asleep brightness* to 1–3 in Settings.

### Undoing device owner / uninstalling

Settings (corner taps + PIN) → **Remove device owner…**. That stops lock task, re-enables the
keyguard, clears the HOME override and the user restrictions and calls `clearDeviceOwnerApp`. Then:

```sh
adb uninstall dev.homekiosk
```

The same thing over adb, for swapping the signing key (e.g. moving from the debug key to a release
keystore — Android refuses to install an APK signed with a different key over a device-owner app):

```sh
adb shell am start -n dev.homekiosk/.MainActivity --ez remove_owner true --es pin 4826
adb uninstall dev.homekiosk
adb install app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner dev.homekiosk/.AdminReceiver   # still needs: no accounts on the device
```

Once the default PIN has been changed, setting a new one by extra needs `--es old_pin <current>`
as well, so an adb user can't reset the PIN and then use it.
`remove_owner` is only honoured when the intent also carries the current PIN (here it checks the
PIN rather than changing it); it is never saved. Preferences survive the uninstall only if you
back them up yourself — expect to run the `am start … --es url …` provisioning line again.

**Exit kiosk (stop lock task)** pauses the lock for 10 minutes (handy for reaching Android
settings via the status bar / *Open Android settings*); it re-locks on its own once the pause is
over and the kiosk is back in front. A device-owner app can't be force-stopped, so don't rely on
a restart to re-lock. A factory reset also removes it.

## HTTP API

All endpoints require the token as `?token=...` or `Authorization: Bearer ...`. With no token
configured every request gets `403`. Responses are JSON; actions return the new status.

| Method | Path | Notes |
|---|---|---|
| GET  | `/status` | `asleep, brightness, awakeBrightness, lastMotionMs, lastTouchMs, battery, charging, plugged, url, appVersion, deviceOwner, lockTask, motionEnabled, motionFraction, printer` (see Bluetooth printer) |
| POST | `/wake` | reason `remote` |
| POST | `/sleep` | reason `remote`; motion is ignored for 10 s afterwards |
| POST | `/reload` | loads the start URL again (also brings back a page that navigated away) |
| POST | `/brightness?value=0-255` | sets the (day) awake brightness |
| POST | `/url?u=<url-encoded>` | sets and loads a new start URL |

```sh
T=YOUR_TOKEN
curl -s  "http://192.0.2.10:2323/status?token=$T"
curl -s -X POST -H "Authorization: Bearer $T" http://192.0.2.10:2323/wake
curl -s -X POST -H "Authorization: Bearer $T" http://192.0.2.10:2323/sleep
curl -s -X POST -H "Authorization: Bearer $T" http://192.0.2.10:2323/reload
curl -s -X POST -H "Authorization: Bearer $T" "http://192.0.2.10:2323/brightness?value=120"
curl -s -X POST -H "Authorization: Bearer $T" \
     "http://192.0.2.10:2323/url?u=$(python3 -c 'import urllib.parse;print(urllib.parse.quote("https://dash.example.com/?view=kitchen", safe=""))')"
```

The server is plain HTTP on the LAN, only runs while the app is alive, and is single-request per
connection (10 s deadline per request). If the port is still held by a previous process at
startup the bind is retried once a minute. Treat the token like a password; don't port-forward it.

## Page integration

```js
if (window.Kiosk?.isKiosk()) {
  const s = JSON.parse(Kiosk.status());    // same JSON as GET /status
}
window.addEventListener('kiosk', (e) => {
  // e.detail = { state: 'awake' | 'asleep', reason: 'motion' | 'touch' | 'timer' | 'remote' }
  if (e.detail.state === 'asleep') showArtScreensaver(); else showDashboard();
});
// Kiosk.wake(); Kiosk.sleep();   // both report reason 'remote'

// Bluetooth printer (384 px wide receipt; any width is scaled, transparency prints as white;
// max 4 megapixels / 8 MB data URL — larger images are refused with an error string, never a throw)
const r = Kiosk.printImage(canvas.toDataURL('image/png'));   // "queued" or an error message
const p = JSON.parse(Kiosk.printerStatus());                 // same as status().printer
window.addEventListener('kiosk', (e) => {
  // printer events carry `printer` instead of `state`; ignore them in asleep/awake handlers
  if (e.detail.printer === 'error') alert(e.detail.message);  // or 'done'
});
```

The first touch on a dimmed screen only wakes it; that gesture is not passed to the page.

## Bluetooth printer

A GB01/GB02/GB03/MX05/06/08/10 thermal "cat printer" (384 px wide). `CatPrinter` holds one BLE
connection for the whole process; `CatPrinterProtocol` is the pure-Kotlin wire format (reference
implementation: `tools/catprint.py`).

- **Discovery**: the first start scans 15 s for those names or service `ae30` and saves the first
  match's address (Settings → *Forget printer* clears it and scans again). Afterwards it
  `connectGatt(autoConnect = true)`s to the saved address, so the printer reconnects by itself
  when switched on. With no connection for 2 min the handle is closed and re-armed (backing off
  2 → 4 → 8 → 15 min, reset once the printer is ready); a connection that fails outright (GATT
  status 133, service discovery timing out, `ae01` missing) goes into the same backoff instead of
  reconnecting immediately. It re-scans when the printer hasn't been seen in this process or for 6 h.
  A print job always tries a direct connect right away regardless of the backoff.
- **Protocol**: write-without-response to `ae01`, notifications on `ae02`. Packet
  `51 78 <cmd> 00 <len> 00 <data> <crc8> ff` (CRC-8 poly 0x07). Job = GET_STATE, QUALITY 0x33,
  ENERGY 0xFFFF, APPLY_ENERGY, UPDATE, LATTICE_ON, one `A2` per row (48 bytes, LSB = leftmost,
  1 = black, Floyd–Steinberg dithered), FEED 90, LATTICE_OFF, GET_STATE. Sent in MTU−3 chunks
  (MTU 185 requested, chunks capped at 182 bytes), one write in flight, ~10 ms pacing; `ae02`
  notifications with byte 2 = `AE` pause (byte 6 = `10`) / resume (`00`) sending.
- **Keepalive**: while connected it writes GET_STATE every 20 s (the printer powers itself off
  when idle; this is an experiment to see if pinging prevents that). Replies are logged at debug.
- **Printing**: jobs are queued and run one at a time. If the printer isn't connected a job waits
  up to 20 s for a connection (longer while a scan is running), then fails with "Printer is off —
  press its power button".
- **State** (`/status` → `printer`, `Kiosk.printerStatus()`): `enabled, state` (`off` / `scanning` /
  `connecting` / `ready` / `printing`), `name, address, lastError, lastPrintAt` (epoch ms).
- **Permissions**: `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT`, auto-granted as
  device owner, otherwise requested once at runtime (like the camera).

```sh
adb shell am start -n dev.homekiosk/.MainActivity --ez print_test true
adb logcat -s CatPrinter:V      # scan / connect / "sent N bytes" / keepalive + notify lines
```

## Home Assistant

**Push (tablet → HA)**: set the webhook URL in Settings. The app POSTs `{"event":"motion"}`
(at most every 30 s) and `{"event":"asleep"}` / `{"event":"awake"}` on state changes.

```yaml
automation:
  - alias: Kitchen kiosk events
    triggers:
      - trigger: webhook
        webhook_id: kitchen-kiosk          # pick something unguessable
        allowed_methods: [POST]
        local_only: true
    actions:
      - choose:
          - conditions: "{{ trigger.json.event == 'motion' }}"
            sequence:
              - action: input_datetime.set_datetime
                target: { entity_id: input_datetime.kitchen_last_motion }
                data: { timestamp: "{{ now().timestamp() }}" }
          - conditions: "{{ trigger.json.event in ['asleep', 'awake'] }}"
            sequence:
              - action: "input_boolean.turn_{{ 'on' if trigger.json.event == 'awake' else 'off' }}"
                target: { entity_id: input_boolean.kitchen_kiosk_awake }
```

**Control (HA → tablet)**: `secrets.yaml` holds the real address/token.

```yaml
# configuration.yaml
rest_command:
  kitchen_kiosk_wake:
    url: !secret kitchen_kiosk_wake_url      # http://192.0.2.10:2323/wake
    method: post
    headers:
      Authorization: !secret kitchen_kiosk_auth   # "Bearer YOUR_TOKEN"
  kitchen_kiosk_sleep:
    url: !secret kitchen_kiosk_sleep_url
    method: post
    headers:
      Authorization: !secret kitchen_kiosk_auth

automation:
  - alias: Wake kitchen kiosk when busy
    triggers:
      - trigger: state
        entity_id: binary_sensor.kitchen_busy
        to: "on"
    actions:
      - action: rest_command.kitchen_kiosk_wake
```

## Settings reference

| Setting | Default | Notes |
|---|---|---|
| Start URL | — | Settings opens automatically on first run when empty |
| Awake brightness | 180 | 0–255 window brightness |
| Asleep brightness | 0 | raise to 1–3 if 0 misbehaves |
| Night window | off, 22:00–06:30, 30 | lower awake brightness inside the window (wraps midnight; start = end means never, not all day) |
| Dim after | 10 min | 0 = never |
| Motion detection | on | front camera; ≥ 3 % of 32×24 blocks changing by > 16 luma |
| Motion debug | off | bottom-left overlay with live changed-% and a flashing dot |
| HTTP port / token | 2323 / empty | API disabled until a token is set ("Generate random token") |
| HA webhook URL | empty | optional |
| PIN | 1234 | change it |

## Untested on hardware

This was built without a device attached. Not yet verified on the A11: lock task / device-owner
provisioning on One UI, brightness 0 behaviour of the panel, front-camera resolution picked by
CameraX and the default motion thresholds, boot relaunch, and battery/charging values under
Protect battery.
