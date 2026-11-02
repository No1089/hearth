# Changelog

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
