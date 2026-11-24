# Changelog

## 1.10.1

- Recipes: Print asks first ("Ingredients" or "Whole recipe", with the length
  in cm), since the printer can't stop once it starts.
- Recipes found on the web get the photo from their source page (schema.org
  image, else og:image); recipes saved earlier without one are looked up once.
- Library cards: three across, and titles no longer clipped.

## 1.10.0

- Home tab: `home_patterns` lights (e.g. the Snowflake) show their running
  pattern on the tile, and the colour panel offers the patterns, strong colours
  and what is running now (pattern, colour, brightness).
- Night button (with `home_night_keep`): tap twice to switch off every Home light
  and switch except the night lamps listed.

## 1.9.0

- Recipes tab. Paste a recipe link and it is read from the page's own recipe
  data (schema.org, which most recipe sites publish). With `anthropic_api_key`
  set, Claude can also find a recipe (searches recipe sites and adapts the best
  one to what you asked, e.g. "chicken curry for 6, no coconut"), write one, or
  make a changed copy of a saved one (vegetarian, no oven, quicker...).
- A recipe shows ingredients with tick boxes and the method. Servings scale the
  quantities, Shopping adds the ingredients you don't have to the shopping list,
  and a Bluetooth receipt printer can print it. New recipes go under Recent
  (the last 12 are kept); Save keeps one.
- Start cooking: one big step at a time, with swipe or Back/Next and the
  ingredients alongside. Times in a step ("simmer for 20 minutes") are timer
  buttons; timers ring with a beep and wake the screen.
- On the Recipes tab the pictures wait `recipe_idle_min` (45) minutes instead
  of 90 seconds, and the screen stays on while a timer runs.
- `/add`: a phone page to send a link or a request to the kitchen; it can save
  the recipe and open it on the tablet. `/add?url=…` fills it in, for a share
  shortcut.

## 1.8.0

- Slideshow: `season_share` gives the paintings in frame-art's monthly theme
  (its new `season` field, e.g. snow scenes for "winter light") a percentage
  of the slides. They repeat, up to 8 times a round, spread evenly and never
  back to back. Default 0 keeps the old even shuffle.

## 1.7.0

- Music: `music_speakers` adds a room picker to the Music tab. Any mix of
  rooms can be picked; Sonos groups them, the group's coordinator stays in
  charge when it is still picked (so playing music spreads), and the volume
  buttons move every picked room by the same step.
- `ha_alerts`: a small amber pill in the dashboard header and on the picture
  screen while an entity is on, e.g. the geyser being shed for load.

## 1.6.0

- Sun/moon button: `entity|Label|moonlight entity|daylight entity` folds a
  ceiling's moonlight into its tile instead of a separate tile. In moonlight the
  tile shows a moon and the slider sets moonlight brightness; the daylight
  entity (optional) is the one that reliably leaves the mode, e.g. the Yeelight
  entity when the main one comes through HomeKit.

## 1.5.0

- `~` in front of a `home_lights` entry keeps it out of All on and Dim (Off
  still includes it): for mode lights such as a ceiling's moonlight, which
  would otherwise fight the main light.

## 1.4.0

- Home tab: lights that can change colour or white temperature get a colour
  button next to their slider. It opens a swatch panel: whites (candle, warm,
  neutral, cool; only those the light can do) and eight pastels.

## 1.3.0

- Home tab: rooms (`#Heading` in `home_lights`), each with its own Off / Dim /
  All on (lights only, so a heater on a switch is never turned on by All on);
  play/pause buttons for `home_media`; plant temperatures in °C; tile labels
  stay on one line.

## 1.2.0

- Home tab: the lights you list (`home_lights`) as big tiles, tap to switch,
  slide to dim, plus All on / Dim / Off; and plant sensors (`home_plants`) with
  soil moisture, temperature, a low battery, and "Needs water" under `plant_dry`.
  The server switches only the listed entities.

## 1.1.2

- Weather works with nothing typed in: blank `weather_lat`/`weather_lon` means
  Home Assistant's own home location.

## 1.1.1

- `/api/image/` serves only what the slideshow lists: pictures, not files in
  hidden folders or a NAS's `@eaDir`, and not other files in the folder.
- Documentation: the watchdog is off after an install and has to be switched
  on, on the Info tab.

## 1.1.0

- A folder of pictures as a slideshow source: `image_dir`, e.g. `/media/hearth`.
  `/media` is now mapped read-only. A subfolder's name is the caption.
- Icon and logo, option labels in the configuration form, and a watchdog so
  the Supervisor restarts the app if the page stops answering.

## 1.0.0

First release as a Home Assistant app. Lists from Home Assistant to-do
entities, the week from calendar entities, the on-a-call banner from binary
sensors or the Mac agent, the kitchen speaker through a `media_player`,
weather from open-meteo, pictures from frame-art or immich, chores in `/data`.
