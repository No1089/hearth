# Running Hearth without a homelab

**Status (2026-10-13):** steps 1 (image), 2 (lists from HA), 3 (frame-art has
a container and a compose file; `IMAGE_DIR` is a folder of pictures) and 4
(add-on: `server/` is the add-on, `repository.yaml` at the repo root) are done.
5 stays as it is. Of 6, the release workflow exists but ships unsigned builds
until a keystore is in the repository's secrets.

**Tested (2026-10-14),** in a throwaway container and on a Yellow: the image
with `LISTS=ha` and `IMAGE_DIR`, the add-on installed from this repository
(options, pictures under `/media`, watchdog on for a day without a restart),
and frame-art's container without a TV. **Not yet:** frame-art pairing with a
Frame from inside its container, and the Linux and Windows busy agents on a
real desk during a call (Linux was run against PipeWire with a virtual
microphone; the Windows script has only been parsed).

Hearth was built against one house: a Proxmox box with Caddy in front, a
personal to-do app, a personal pocket-money app, and a Home Assistant Yellow.
This is the plan for letting someone else run it with nothing more than the
Home Assistant they already have and a spare tablet.

## What it depends on today

| Needs | For | Someone else has it? |
|---|---|---|
| Home Assistant | calendar, busy sensors, music, wake sensor | Usually: it's the audience. |
| open-meteo | weather | Yes, free, no key. |
| [frame-art](https://github.com/No1089/frame-art) | the paintings | Public; systemd units on a Debian box, or a container (`ghcr.io/no1089/frame-art`). |
| the todo app | shopping list, to-do sections, Particle printing | No: private, personal. |
| the pocket-money app | balances and goals | No: private, personal. |
| a reverse proxy with TLS | the tablet's URL | Varies. Plain HTTP on a LAN is acceptable for a kiosk. |
| a busy agent | the on-a-call banner | Mac, Linux (PipeWire) and Windows agents in this repository; the contract is three fields ([mac/README.md](../mac/README.md)). |
| the Android SDK | building the kiosk app | No. |

Weather and HA are fine. The two private apps are the real wall, and the
paintings and the APK are friction.

## The plan, in order of payoff

### 1. A container image (small)

A `Dockerfile` for `server/` and a GitHub Actions job that publishes
`ghcr.io/no1089/hearth` on every push to `main`. Then it runs anywhere a
container does, with one env file:

```sh
docker run -d --name hearth -p 5007:5007 --env-file deploy.env \
  -v hearth-data:/app/data ghcr.io/no1089/hearth
```

Synology Container Manager, a Raspberry Pi, Unraid, the Home Assistant host's
own Docker: all the same command. This also fixes the current compose file's
habit of `pip install`ing on every start, which needs the internet to boot.

### 2. Lists from Home Assistant instead of the todo app (medium)

Home Assistant has `todo.*` entities with a stable API: the built-in Shopping
List and Local To-do, plus Google Tasks, Todoist, Microsoft To Do and others.
Hearth's list code already sits behind two functions (`_list`, `_summarise`)
and four routes, so a second backend is a contained change:

- `LISTS=ha` in the env. Shopping = one `todo.*` entity; the To-do tabs = every
  other `todo.*` entity, named by its friendly name, with `TODO_FIRST` /
  `TODO_HIDE` as now.
- Read with the websocket `todo/item/list`; write with the `todo.add_item`,
  `todo.update_item` and `todo.remove_item` services.
- "Bought before" suggestions come from completed items, which HA keeps until
  they're cleared, so the one-tap re-add works the same way.
- Printing already goes to the Bluetooth printer from the tablet, so the
  Particle path simply isn't there in this mode.

With this, lists need nothing but HA, and voice assistants that can add to an
HA to-do list get the kitchen list for free.

### 3. Pictures without frame-art (small)

Three sources the slideshow mixes: the frame-art API, an immich album (needs a
key), and a plain folder of pictures, `IMAGE_DIR`, mounted into the container
or, as an add-on, under Home Assistant's `/media`. A subfolder's name is the
caption. An iCloud Shared Album is on the list.

frame-art stays the nicest option, and it now runs as a container too:
`docker compose up -d` in that repo starts the gallery and a scheduler that
runs the monthly pipeline and the rotation check, with the library in one
directory. Without a Frame TV it still fetches and renders, which is all
Hearth needs. The image is `ghcr.io/no1089/frame-art`.

### 4. A Home Assistant add-on (medium)

For people on Home Assistant OS (the Yellow, the Green, a Pi) this is the
"no server" answer: install from an add-on repository, fill in a form, done.

- An add-on repo with `hearth/config.yaml`, `Dockerfile` (the one from step 1,
  on the HA base image), `ports: {"5007/tcp": 5007}`, and
  `homeassistant_api: true`, which hands the add-on a Supervisor token. No
  long-lived token to create, no HA URL to type: Hearth talks to
  `http://supervisor/core/api` and the websocket at the same place.
- The add-on's option schema becomes the env file: calendars, busy entities,
  music entity, PIN, max volume.
- The chores file lives in `/data`, which every add-on gets and which is in
  its backups. `/media` is mapped read-only for a folder of pictures.
- No ingress. Ingress needs an HA login, which a kiosk can't give; the exposed
  port is what the tablet opens.

Steps 2 and 4 together are the real goal: HA plus a tablet, and nothing else.

### 5. Pocket money (leave optional)

The Pocket tab only appears when a pocket-money URL is configured, and that
app isn't public. Leave it that way. If there's demand, a chores-only mode
(kids tick chores, no balances) needs no backend at all, since the chores
already live in Hearth's own data file.

### 6. The tablet app and the Mac agent (small)

- Publish a signed APK with each GitHub release, so nobody installs an Android
  SDK. Keep the package name `dev.homekiosk`: changing it drops device owner
  on every installed tablet.
- The busy agent builds with `install.sh` on any Mac with the command-line
  tools. A notarised binary in the release would spare that; worth it once
  anyone other than its author wants it.
- Provisioning stays the same twelve adb lines, in
  [android/README.md](../android/README.md).

## What's not in scope

Multi-house hosting, accounts, anything that reaches out of the home network.
Hearth is a page on the kitchen wall. The whole point of the design is that
nothing about it leaves the house, and a hosted version would break that.
