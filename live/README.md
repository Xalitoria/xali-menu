# Live tests

What the unit tests cannot show: the plugin on a real Folia server, with a bot (mineflayer) as the player. Nothing here
runs in `./gradlew test`; it is for checking a change by hand, and for finding what a mock would have hidden.

```console
user $ menu/live/setup.sh
user $ menu/live/server.sh start
user $ node /tmp/xm-live/bot/act.js > /tmp/xm-live/act.txt; cat /tmp/xm-live/act.txt
user $ menu/live/server.sh stop
```

`XM_PAPER=<jar>` makes the server a Paper one instead of the dev server's Folia. `XM_PLUGINS="Name ..."` copies plugins of the dev server by the start of their jar name. `XM_RELAY=<url>` sets the relay of `/xmenu share`. `XM_EDITOR=1` turns the web editor's endpoint on (`XM_EDITOR_PORT`, `XM_EDITOR_PACK` for a pack zip, `XM_GLYPHS` for a Nexo glyphs folder); `/xmenu edit` then prints a link, and `curl` with its token tries the endpoint. `XM_DIR` (default `/tmp/xm-live`) says where everything is put and `XM_PORT` (default 25599) which port the server uses.
The server is offline-mode and meant to be thrown away. Do not pipe a bot into `head`: closing the pipe ends it early.

| Script | Menu | Shows |
|---|---|---|
| `bot/act.js` | `t` (`/xt`) | The actions: items given, taken by name and cleared, potion, teleport, game mode, health and food, levels, title, boss bar, book, particle, `run-for`, `random` and the `input` prompt with its timeout and cancel word. |
| `bot/cond.js` | `c` (`/xc`) | The conditions, by which items `view` leaves in the menu after each change of state: free slots, held item, game mode, cooldowns and variables. |
| `bot/props.js` | `i` (`/xi`) | The item properties: it gives one item of every kind; then read the saved player data with `python3 bot/nbt.py <server>/world/players/data/<uuid>.dat` (Folia has no `/data` for entities). |
| `bot/complete.js` | `a` (`/xa`) | Tab completion of a menu's `args` from its `complete` map: players, fixed words, the rest of the line. |
| `bot/addon.js` | `e` (`/xe`) | An activator added by another plugin, `addon/XmDemo.java` (built with `XM_ADDON=1` at setup): `/xmwave` fires it with values; matching, a gate, `cancel`, and the keys it refuses. |
| `bot/layout.js` | `l` (`/xl`) | A `layout` with a `legend`: the item in every slot of the window. |
| `bot/npc.js` | `n` (`/xn`) | FancyNpcs `npc` activators: creates an NPC and right- and left-clicks it (set up with `XM_PLUGINS=FancyNpcs`). |
| `bot/skin.js` | `s` (`/xs`) | The `skin` and `skin-reset` actions with SkinsRestorer (`XM_PLUGINS=SkinsRestorer`): what it stores for the player after each, and a skin that does not exist. |
| `bot/compare.js` | imported DeluxeMenus menus | Opens each menu with DeluxeMenus and with XaliMenu and prints what differs (items, names, lore, components, the chat from clicks). Needs `XM_PLUGINS=DeluxeMenus` on Paper (`XM_PAPER=<paper jar>`, DeluxeMenus does not support Folia) and `xmenu import deluxemenus` run first; `dm/compare_menu.yml` is a menu with most item options for it. |
| `bot/region.js` | `r` (`/xr`) | WorldGuard: `region-enter` and `region-leave` activators and the `region` condition (`XM_PLUGINS="FastAsyncWorldEdit worldguard"` on Paper). |
| `bot/editor.js` | none | Joins, prints the link of `/xmenu edit` (set up with `XM_EDITOR=1`, and `op Tester` from the console once it has joined) and stays online so that `curl` with the link's token can try the endpoint for a player who is on the server, such as `POST /v1/elements` with `{"catalog": {"type": "players"}}`. Ends when its stdin closes. |
| `bot/relay.js` | none | `/xmenu share` through the relay (`XM_RELAY=<url>` at setup, `node menu/relay/relay.js` running), then the page's side of the link (`editor/web/src/relay.js`): reads the menus, saves one, which is held; `/xmenu edits`, `apply` and `drop` are then run from the console. Needs `op Tester`. |
| `bot/tools.js` | `c` | `/xmenu inspect`, `info`, `validate` and `reload <menu>` (the answers are in `server/server.log`). |

`menus/` has the menus these use; `i.conf` holds one item that is wrong on purpose, for `/xmenu validate`.

## Why the setup is odd

- **mineflayer** knows protocol 775 (Minecraft 26.1) and the dev server's Folia speaks 776 (26.2), so `setup.sh` gives the 26.1
  data the number 776 and joins as `26.1`. Packet warnings are noise (`hideErrors`).
- The **plugin** asks the server for `com.xalitoria:util` at the version of the workspace, which may not be published yet. `setup.sh`
  puts the jars the workspace just built into the server's library folder, where the loader finds them.
- Item names from mineflayer are those of 26.1; the window title and custom names are right.
- Kill the server with `server.sh stop`, never by pattern with `pkill -f`.
