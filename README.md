# home-dash

A kitchen wall dashboard on a spare Android tablet, replacing a hacked "smart frame".

- **`server/`**: a small Flask app plus one static page. When idle it shows a slideshow of the
  art gallery and an immich album. Touching it opens the dashboard: the week's calendar,
  the shopping list, family to-dos, and an office **busy** banner.
- **`android/`**: a minimal kiosk wrapper for the tablet. It runs a full-screen WebView,
  watches the front camera for motion to wake the screen, dims the backlight instead of
  sleeping, and has a small HTTP API so Home Assistant can wake it.

The server stores no data of its own:

| Feature  | Source                                                                              |
|----------|-------------------------------------------------------------------------------------|
| Lists    | the todo app (sections `shopping` / `family`), which also prints via the receipt printer (`POST /api/print-section/<id>`) |
| Calendar | Home Assistant `calendar.*` entities (e.g. the Google Calendar integration)          |
| Busy     | Home Assistant binary sensors; any one `on` shows the banner (the HA macOS app's mic/camera-in-use sensors work well) |
| Pictures | the art gallery's `/api/works` + immich albums (the server proxies album assets only, so the API key stays server-side) |
| Weather  | open-meteo                                                                           |

## Run

```sh
cd server
cp deploy.env.example deploy.env   # fill in
docker compose up -d
```

Behaviour: after 90 s untouched, the dashboard returns to the pictures. The page switches to
a dark theme from 20:00 to 07:00. Inside the kiosk app, the page listens for
`kiosk` events (`asleep` / `awake`) and pauses the slideshow while the screen is dimmed.
Open `/#dash` to start on the dashboard rather than the pictures.
