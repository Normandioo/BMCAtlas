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
