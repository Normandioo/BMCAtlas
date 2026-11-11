# BMC Atlas

**Fabric** client mod for **Minecraft 1.21.11 and 26.x**: a full-screen political map and a minimap with countries, provinces, cities, live war tracking and online players. It reads the server's public territory data (GET only) and never changes anything on the server or the site.

## Features

- **Map modes**: physical, political, provinces, diplomacy and war.
- **War mode**: sides, taken and contested cities, fronts with direction arrows, In Play and encircled cities, capitulation tracking (Law 7) and a "what if?" simulator. With live control on, city holders follow the dynmap markers.
- **Minimap** on the game screen, with a banner when a city is taken (click it with chat open to jump to the city on the map).
- **Search** for countries, cities and players, plus distance measuring and your own marked points.
- **Country labels** fitted inside each territory, city icons and names, online players.
- **Diplomacy panels**: alliances, NAPs, truces, ultimatums, purge deadlines, diplolock and world history. The country list also has a ranking by map area.
- **Settings** (gear icon): appearance, players, war, minimap and server maps. Defaults are already tuned for the server.

## Controls

- `N`: open the map. `Z`: enlarge the minimap (hold, or toggle: see settings). `=` / `-`: minimap zoom. All four can be rebound.
- On the map: drag or arrows / `WASD` to move, wheel or `+` / `-` to zoom, click to select, `1`-`5` to switch modes.
- `F` or `/`: search (countries, cities, players, alliances, provinces and your points). `Space`: center on you. `C`: copy the coordinates under the cursor.
- `M`: mark a point (click a point to rename, recolor or delete it). `R`: measure a route with any number of points.
- `H`: list of keys. `F7`: performance overlay.

## War recording

Turn on **Recording** on the bar at the bottom of the war map (or in the settings). Every change in who holds the cities of the followed war, and where the players on the map are, is saved in a folder per war in `bmcatlas/recordings/` (inside the game folder); it keeps recording with the map closed. The setting "Record live wars automatically" starts it by itself during a live war. The same bar plays the recording back on the map at 0.25x to 300x, with the players moving, the capture waves, and score and fronts as they were; click or drag its timeline to go to any moment. "Recordings" opens the replay editor: every recording, its highlights (captures, capitals, battles...) and the clips that will make the video.

## Server plugin (BmcWar)

On a server running the BmcWar plugin (0.18.0 or newer), the plugin tells the mod through the game connection (channel `bmcatlas:sync`) who holds each city of the live wars, the bridges the staff opened or closed, and when the site data changed, only when something changes. The mod then reads none of that from dynmap or the site by itself: it downloads `territories.json` once when the server says the site changed. On a server without the plugin, the mod asks dynmap and the site as before. Online players still come from dynmap.

## Configuration

- Server and map settings: `config/bmcatlas/site.json` (`url`, `mapsUrl`, `dynmapUrl`, `dynmapWorld`, `worldHalfSize`).
- Player preferences: `config/bmcatlas/prefs.json`.
- Manual countries (when there is no site data): `config/bmcatlas/countries.json` (`id`, `name`, `color` as `#RRGGBB`).
- Explored terrain and province owners per world: `bmcatlas/` in the game folder. Downloaded images are cached in `config/bmcatlas/cache/`.

To use the mod on another server, point `url` at that server's `territories.json` (and optionally `mapsUrl` and `dynmapUrl`). Without `url`, the map works only with the chunks you explore.

## Build

```
gradle build
```

builds the Minecraft 1.21.11 jar. The source is written once, for 1.21.11; `python3 tools/port26.py` rewrites the tree for Minecraft 26.x (renamed classes, `port26/Compat.java`, build files), after which the same command builds the 26.x jar (Java 25). CI does both on every push to `main` and publishes one release with the two jars.
