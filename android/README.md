# Home Kiosk (Android)

A small home-made replacement for Fully Kiosk Browser, built for a Samsung Galaxy Tab A11 8.7"
(1340×800, Android 15 / One UI) hanging in the kitchen and showing the home-dash web dashboard.

- Full-screen, immersive, landscape-locked WebView (JS, DOM storage, autoplay without gesture).
- Reconnect screen + retry with backoff (5 s → 60 s) on load errors / 5xx / renderer crashes; reloads
  the page once it is older than 24 h.
- Kiosk lock when provisioned as **device owner**: lock task, no keyguard, stay-on-while-plugged,
  persistent HOME activity, camera permission auto-granted. Without device owner it's a normal app
  that can still be chosen as the launcher.
- **Dims instead of sleeping** (the camera stops when the screen really turns off): window brightness
  → 0 plus a black overlay. Touch, motion, `/wake`, or `Kiosk.wake()` wakes it. Auto-dim after N
  minutes (default 10) without motion/touch; optional night window with a lower awake brightness.
- Front-camera motion detection (CameraX `ImageAnalysis`, ~320×240, 2 fps, 32×24 block grid).
- Page integration: `kiosk` DOM events and a `window.Kiosk` bridge.
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
./gradlew test              # JVM unit tests (motion detector, HTTP parsing, night window)
```

`local.properties` (gitignored) is optional when `ANDROID_HOME` is set.

## Provisioning the tablet

1. **Protect battery** — it will be on the charger 24/7: Settings → Battery → (More battery settings) →
   Protect battery (85 % cap). Do this first; it survives as a normal setting.
2. **Factory reset** (Settings → General management → Reset → Factory data reset).
3. In the setup wizard **skip the Google account and the Samsung account**, connect to Wi-Fi.
   `dpm set-device-owner` refuses to run if *any* account exists on the device.
4. Enable developer options (Settings → About tablet → Software information → tap *Build number* 7×),
   then Developer options → **USB debugging** on. Connect USB, accept the RSA prompt.
5. Install and provision:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner dev.homekiosk/.AdminReceiver

# Headless config (all optional except url/token). --es values are strings; --ei/--ez also work.
adb shell am start -n dev.homekiosk/.MainActivity \
    --es url "https://dash.example.com/" \
    --es token "$(openssl rand -hex 24)" \
    --es webhook "https://ha.example.com/api/webhook/kitchen-kiosk" \
    --es pin 4826
```

Other accepted extras: `port`, `brightness` (0–255), `night` (true/false), `night_start`/`night_end`
(`HH:MM`), `night_brightness`, `sleep_minutes`, `motion` (true/false), `motion_debug` (true/false).

The first `am start` (or a reboot) applies the device-owner policies and enters lock task. Check:

```sh
adb shell dumpsys device_policy | grep -A3 "Device Owner"
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
  home, recents, notifications and the status bar are blocked.
- Samsung's "Adaptive brightness" and screen timeout no longer matter: the app keeps the screen on
  (FLAG_KEEP_SCREEN_ON + STAY_ON_WHILE_PLUGGED_IN) and overrides window brightness itself.
- If brightness 0 turns the backlight fully off or behaves oddly on this panel, raise
  *Asleep brightness* to 1–3 in Settings.

### Undoing device owner / uninstalling

Settings (corner taps + PIN) → **Remove device owner…**. That stops lock task, re-enables the
keyguard, clears the HOME override and calls `clearDeviceOwnerApp`. Then:

```sh
adb uninstall dev.homekiosk
```

**Exit kiosk (stop lock task)** only suspends the lock until the app restarts (handy for reaching
Android settings via the status bar / *Open Android settings*). A factory reset also removes it.

## HTTP API

All endpoints require the token as `?token=...` or `Authorization: Bearer ...`. With no token
configured every request gets `403`. Responses are JSON; actions return the new status.

| Method | Path | Notes |
|---|---|---|
| GET  | `/status` | `asleep, brightness, awakeBrightness, lastMotionMs, lastTouchMs, battery, charging, plugged, url, appVersion, deviceOwner, lockTask, motionEnabled, motionFraction` |
| POST | `/wake` | reason `remote` |
| POST | `/sleep` | reason `remote`; motion is ignored for 10 s afterwards |
| POST | `/reload` | reloads the WebView |
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
connection. Treat the token like a password; don't port-forward it.

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
```

The first touch on a dimmed screen only wakes it; that gesture is not passed to the page.

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
| Night window | off, 22:00–06:30, 30 | lower awake brightness inside the window (wraps midnight) |
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
