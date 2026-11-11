## 1.5.0

- Replay editor: the "Recordings" button on the war map bar lists every recording (war, date, length, sessions, whether it has players); opening one shows it on the map with a timeline of the whole recording, every session, with the time away in grey
- Highlights found by themselves and marked on the timeline: captures, cities retaken, contested, capitals, capitulations, battles (3 or more players of 2 sides close together for 10 s); click one to go there
- Clips for the video: In/Out marks and "Add clip", or "Auto highlights" (each highlight from 20 s before to 15 s after, with the stretches in between at 120x); drag a clip to move it, or its ends to resize it; each clip has its own speed. Saved in project.json in the recording's folder
- Keys: Space play, Left/Right 1 s, Shift+Left/Right highlights, I/O, Enter, Delete, Home/End, Esc
- The recording bar on the war map has a clear Start recording / Stop recording button (with the time of the recording running) and folds away with its arrow; folded, it keeps a small "Recorder" button and the red REC while recording- Stop recording also holds back "Record live wars automatically" for that war until you start it again- Any text cut short with "..." in the map menus shows the whole text when the mouse is over it

## 1.4.0

- Replay of a war recording: the players move on the map, with the faces they had when it was recorded, and the cities change hands with the capture wave
- The bar on the war map has a timeline of the most recent session of the recording: click or drag it to go to any moment; the marks are where cities changed hands
- Playback speed from 0.25x to 300x with the - and + buttons; "<" and ">" jump to the previous and next change of cities
- The map can be moved and zoomed anywhere while a recording plays
- While a recording is on screen nothing is recorded and dynmap is not asked

## 1.3.0

- War recordings get a new format: one folder per war in `bmcatlas/recordings/` (inside the game folder), with who held each city, where every player on the map was (dynmap's readings plus the exact positions of the players near you), their faces and the site data in use. This is the base of the video replay coming next
- Recording goes on with the map closed, and coming back to the same war continues the same recording (the time you were away shows as a gap)
- New setting "Record live wars automatically" (off by default)
- Old recordings in `config/bmcatlas/recordings/` are imported the first time (the old file is kept, renamed to `.jsonl.imported`)
- Recordings stay on your computer: nothing is sent anywhere

## 1.2.6

- On a server with the BmcWar plugin (0.19.0 or newer), the mod tells the plugin its version, so the server can warn you when your mod is out of date (only that: nothing else is sent, and only to servers with the plugin)

## 1.2.5

- The mod tells you when a newer version is out: once per game start it reads the latest release on GitHub and, the first time you are in a world, shows a chat message with a link to the download page. Turn it off in the settings ("Tell me when a new version is out")

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
