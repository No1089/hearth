# Changelog

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
