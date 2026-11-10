## 1.2.4

- On a server with the BmcWar plugin (0.18.0 or newer), the server tells the mod who holds each city of the live wars, the bridges the staff opened or closed, and when the site data changed. The mod then stops asking dynmap for the war markers every few seconds and the site every half hour, and site changes show up as soon as the server sees them. On other servers nothing changes

## 1.2.3

- Internal: the map screen code is split into smaller files (MapUi, MapPanels and helpers); no change to gameplay or interface
- Automatic tests for the war score, war fronts, relation colours and the map list, run on every build

## 1.2.2

- Internal cleanup: removed unused code (no change to gameplay or interface)

## 1.2.1

- Each menu of the map (side panel, wars / legend box, mode bar) is resized on its own by dragging its corner, like an image in an image editor (50-200%; also in the settings)

## 1.2.0

- Map: smooth zoom, keyboard controls (move, zoom, search, center, help with `H`), `C` copies the coordinates under the cursor
- Marked points: names, colors, shown on the minimap and found by the search
- Ruler: routes with any number of points, with times on foot, sprinting, by boat and by elytra
- Search: alliances, provinces and marked points; ignores accents; more results
- The map remembers its mode, zoom and the city / player switches
- Minimap: arrow showing where you look, zoom keys, hold or toggle, opacity, allies in green and enemies in red during a war, online players refreshed every 3 s
- War recording: record the war map over time and play it back
- A note on the map tells how old the site data is, and warns when the site cannot be reached
- Menu size: automatic from the resolution of the game window, or set by hand (60-150%) in the settings
- One source for every Minecraft version; each release has both jars. License file and mod icon added

## 1.1.0

- Terrain cache: one file per region, written in the background and only when something changed (no more freeze when the map is saved or closed)
- Memory: terrain of regions out of use goes back to disk instead of staying in memory for the whole session
- Minimap: regions are repainted only when a chunk really changed
- Flags, icons and player faces are cached on disk; failed downloads are retried; unused textures are released
- Territory data is only downloaded and repainted when the site has something new
- The old terrain file is converted on first start and kept as `.regions5.old`

## 1.0.1

- Far fewer requests to the server website: online players are refreshed every 2 s with the map open and every 5 s for the minimap (was every second), war markers every 3 s / 6 s
- Requests back off after errors and pause while the game window is not focused

## 1.0.0

First stable release.

- Political, province, diplomacy, war and physical map modes
- Live war tracking with fronts, capture notices and capitulation tracking
- Minimap with player head marker; hold Z to enlarge
- Country highlight (zoom to fit, capital centering, gold border)
- Sharper city icons with adjustable size
- Diplomacy panels, country ranking and world history
- English interface; defaults tuned for BMC servers
- Two files: Minecraft 1.21.11, and Minecraft 26.1–26.3
