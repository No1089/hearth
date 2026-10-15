# Hearth

Hearth is a dashboard for a spare tablet on the kitchen wall. Left alone, it
shows public-domain paintings and family photos. A tap shows the week's
calendar, the shopping list, the to-do lists, the kids' pocket money and
chores, and the kitchen speaker. A red "on a call" banner tells the kids when
not to run into the office. It is one page served by a small Python app; the
tablet runs a kiosk browser pointed at it.

As an add-on it owns almost nothing. Lists are Home Assistant to-do entities
(the built-in Shopping List and Local To-do, or Google Tasks, Todoist and the
like). The calendar, the busy and wake sensors and the speaker are Home
Assistant entities. Weather comes from open-meteo, with no key. Pictures come
from a [frame-art](https://github.com/No1089/frame-art) gallery, an immich
album, or a folder of pictures under Home Assistant's `/media`. Only the
chores live in the app's own data directory, which is backed up with the app.

## Install

Settings → Apps (Add-ons, until recently) → Install app → ⋮ in the top right →
Repositories, add `https://github.com/No1089/hearth`, and Hearth appears in
the store. Install it, fill in the Configuration tab, start it. The image is
built on the Home Assistant box the first time, which takes a minute or two on
a Yellow.

Then turn on **Watchdog** on the Info tab. The Supervisor leaves it off after
an install; on, it restarts the app if the page stops answering.

Without a public repository, the same folder works as a local app: copy
`server/` to `/local_apps/hearth` (over the SSH app, or Samba, which still
shows the share under its old name, `addons`) and it appears under "Local
apps" in the store.

## Opening it

The tablet opens **`http://<home-assistant-ip>:5007`**, which is also what
**Open Web UI** on the add-on page does. There is no ingress, on purpose:
ingress needs a Home Assistant login, and a kiosk can't give one. Hearth has no
login of its own either, so keep the port on the home network.

The add-on has `homeassistant_api`, so it talks to Home Assistant through the
Supervisor. There is no long-lived token to create and no URL to type.

## Options

Every option is the app's environment variable, lowercased. A blank means
"off" or "the default"; lists of entities are comma-separated. Lists always
come from Home Assistant to-do entities, and the time zone is Home Assistant's
own, so neither is an option.

| Option | Default | What it does |
|---|---|---|
| `ha_shopping_list` | `todo.shopping_list` | The to-do entity shown as the shopping list. The default is the Shopping List integration's entity; without that integration, name a Local To-do list (or any other `todo.*`) here, or the shopping tab says the entity is missing. Ticked items come back as one-tap "bought before" buttons until Home Assistant clears them. |
| `ha_todo_lists` | *(none)* | To-do entities shown as tabs, in this order. Empty shows every other `todo.*` entity, named by its friendly name. |
| `todo_first` | *(blank)* | Entity ids to put first among the tabs. |
| `todo_hide` | *(blank)* | Entity ids to keep off the kitchen screen. |
| `ha_calendars` | *(blank)* | Calendars for the week view, `entity|Label|#colour`: `calendar.kids|Kids|#e4572e,calendar.family|Family`. Label and colour are optional. |
| `ha_busy_entities` | *(blank)* | Any of these `on` shows the banner. |
| `ha_wake_entities` | *(blank)* | Any of these `on` wakes the screen, e.g. a kitchen occupancy sensor. |
| `busy_label` | `On a call` | The text on the banner. |
| `music_entity` | *(blank)* | A `media_player`, e.g. the kitchen Sonos. Blank: no Music tab. |
| `music_max_volume` | `0.6` | Volume cap, 0 to 1. |
| `pocket_url` | *(blank)* | The pocket-money app. Blank: no Pocket tab. |
| `pocket_pin` | *(blank)* | Parent PIN that credits a pocket-money day and edits chores. |
| `art_url` | *(blank)* | A frame-art gallery for the slideshow. |
| `immich_url` | *(blank)* | An immich server for the slideshow. The key stays in the add-on; the page never sees it. |
| `immich_api_key` | *(blank)* | Its API key. |
| `immich_album_ids` | *(blank)* | Album ids to show. |
| `image_dir` | *(blank)* | A folder of pictures for the slideshow, e.g. `/media/hearth` (the app sees `/media` read-only). Subfolders are included, and a subfolder's name is the caption, so `/media/hearth/Cornwall 2024/` captions its pictures "Cornwall 2024". |
| `weather_lat`, `weather_lon` | *(blank)* | Where the weather is for. Blank: Home Assistant's own home location. |

## The on-a-call banner

The banner needs something to say when a call is on. Any Home Assistant
`binary_sensor` in `ha_busy_entities` will do. For a Mac in the office, the
companion app's camera and microphone sensors went unavailable on macOS 26, so
the repository has a small agent that asks the operating system directly and
reports to Hearth:
[mac/README.md](https://github.com/No1089/hearth/blob/main/mac/README.md).
`mac/install.sh http://<home-assistant-ip>:5007` builds it and runs it at
login.
