# BMC Atlas

**Fabric** client mod for **Minecraft 1.21.11**: a full-screen political map and a minimap with countries, provinces, cities, live war tracking and online players. It reads the server's public territory data (GET only) and never changes anything on the server or the site.

## Features

- **Map modes**: physical, political, provinces, diplomacy and war.
- **War mode**: sides, taken and contested cities, fronts with direction arrows, In Play and encircled cities, capitulation tracking (Law 7) and a "what if?" simulator. With live control on, city holders follow the dynmap markers.
- **Minimap** on the game screen, with a banner when a city is taken (click it with chat open to jump to the city on the map).
- **Search** for countries, cities and players, plus distance measuring and your own marked points.
- **Country labels** fitted inside each territory, city icons and names, online players.
- **Diplomacy panels**: alliances, NAPs, truces, ultimatums, purge deadlines, diplolock and world history. The country list also has a ranking by map area.
- **Settings** (gear icon): appearance, players, war, minimap and server maps. Defaults are already tuned for the server.

## Controls

- `N`: open the map. Hold `Z`: enlarge the minimap while the key is down.
- Drag to move, wheel to zoom, click to select, `1`-`5` to switch modes, `F7` for the performance overlay.

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

The jar is in `build/libs/`. CI builds every push to `main` and publishes a release.
