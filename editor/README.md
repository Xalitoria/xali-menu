# XaliMenu editor

**The XaliMenu editor** is a web editor for XaliMenu's menu files, hosted at <https://menu.xali.to>. It edits the same files the plugin reads: it shows each menu
as a form (every action, condition, activator and item property, with the comments of the source as help), as the window with its
items, and as text. It checks a menu while you type, shows the glyphs and custom items of the server's resource pack, and moves
glyphs in titles into place.

## Contents

- [How it works](#how-it-works), [How items and windows are drawn](#how-items-and-windows-are-drawn)
- [Using it](#using-it)
- [Setting it up](#setting-it-up): [Host the site](#1-host-the-site-once-for-the-organization),
  [Turn on the endpoint](#2-turn-on-the-endpoint-on-a-server-optional), [Share through a relay](#3-share-through-a-relay-optional),
  [Show the resource pack](#4-show-the-resource-pack-optional)
- [Development](#development)
- [See also](#see-also)

## How it works

The editor is static files: HTML, CSS and JavaScript with no build step and no dependencies. GitHub Pages serves them, and nothing
else has to be hosted for it. A relay is there for servers that cannot be reached from outside (below), and there is no database. In particular:

- **The form is generated from the plugin.** `web/schema.json` is written by `./gradlew :menu:editor:schema` from the serializer
  descriptors that read the menu files, and the comments of the Kotlin sources. `./gradlew :menu:editor:test` fails when the file
  is older than the code, so the editor cannot drift from the plugin.
- **A menu reaches the editor in one of three ways.** With `editor.enabled` in `config.yml`, the plugin listens on a port and the
  browser talks to it directly (the link from `/xmenu edit` holds a token). Without it, the link holds the menu itself, compressed,
  after the `#`; the browser never sends that part to any server. The editor then gives a `.conf` file to put in the menus folder.
  The third way is a relay, for a server that cannot be reached from outside: see "Sharing through a relay".
- **Saving is checked twice.** The editor reports what it can see; the plugin reads the menu as it would at startup before it
  replaces the file, and refuses one that would not load. The old file is kept as `<name>.conf.bak`.
- **The resource pack stays in the browser.** The server sends its pack (or you pick the zip), the page reads the fonts and
  textures out of it, and nothing is stored or uploaded.

LuckPerms' editor works through two services run for it (a paste service and a socket relay). GitHub Pages cannot run services,
so by default this editor connects the browser to the server itself, or uses a file. The relay below is the same idea as LuckPerms',
made so that the relay can only see encrypted bytes and never holds a menu.

## How items and windows are drawn

The editor draws what a player sees, from the same files the game reads, with no model of its own for the common items:

- **Items** are drawn from their item definition (`assets/<namespace>/items/<name>.json`) and model json, found in the resource
  pack first and in the game's own files after it. The `gui` display transform, the model's parents, element rotation, texture
  rotation, tints (dyed leather, grass, potions), `custom_model_data` and `item-model` choices are followed, and the result is drawn
  in software with the game's two item lights and nearest-neighbour textures, at four times the size and shrunk, so edges are smooth (the
  game itself draws without that, and the code can too). The size is the GUI scale of the toolbar, 1 to 6 or fit to the space. Compared
  with icons the game rendered itself (the wiki's), stone, a furnace, stairs and a sword differ in at most a few of about six hundred
  pixels, and a creeper head and a shulker box in none.
- **Drawn from code in the game**, and so built here from boxes with the game's texture layout: banners (the base color and each pattern in its dye), player and mob heads (a `texture:`
  material shows its skin, fetched through the server because Mojang's texture host does not allow other sites), chests and shulker
  boxes. Shields, signs, armor trims and the animation of compasses and clocks are not drawn that way yet: they show a tile. Beds and
  other items the game draws as several models (`transformation` in the item definition) are drawn from their models.
- **The scale** is the game's GUI scale (1 to 6, or fit): each pixel of the game is a whole number of screen pixels. When the browser is
  zoomed (or the display has a pixel ratio above 1) the preview takes the nearest whole number of screen pixels, so it stays sharp and still
  grows with the zoom; the rest of the page scales as the browser makes it. `src/scale.js` has the arithmetic and its tests.
- **The player's inventory** is part of the window, under the menu, and a menu can draw in it: click one of its slots (or `+ Add` under
  "Player inventory" in the list) and the item is saved in the menu's `inventory`, with the slot numbers of the game (0 to 8 the hotbar, 9 to 35
  the rows above it). Items there are edited like the others and move by dragging, but only within the inventory. An item of the menu itself with a
  slot past the menu's size is shown in the inventory marked in red, and reported as a problem: it is never drawn.
- **The window** fills the space between the toolbars and is always on the screen. It takes the biggest GUI scale at which it fits (or the scale you chose) and
  the rest of the space is room around it, for glyphs that are used as overlays and reach past the window. It is the game's container texture for the inventory
  type (chest, hopper, dispenser, dropper), the title is in the game's font with the glyphs of the resource pack, and the player's inventory is below it. Counts
  and the durability bar are drawn as the game draws them, and the tooltip uses the game's own sprites, with the name, enchantments and lore.
- **The game's files** are read from a public copy of Mojang's assets (`editor/web/src/assets.js` names it and the version) and are not
  part of this repository. Everything is fetched the first time it is needed, so the first draw of a menu takes a moment.
- Not exact: the shimmer of an enchanted item (it moves in the game; one moment of it is shown), `head:<name>` heads (they use the default skin; only `texture:` skins are fetched).
- Text the game's own pictures lack is drawn with its fallback font, Unifont (`src/unihex.js`: the `unihex` providers, half size, `size_overrides`), read
  once and only when such a character appears. The zip files come from the public copy when it has them, else (connected to a server) from
  `GET /v1/game-file?name=font/unifont.zip`, which downloads the file from Mojang's servers for the server's version, checks its hash, and keeps it in
  `plugins/XaliMenu/cache/game`. It will fetch no file but a zip of the game's `font` folder.

## Using it

Run `/xmenu edit` in the game or the console, and open the link. `/xmenu edit <menu>` opens one menu first. Without the endpoint,
`/xmenu edit <menu>` is needed, since the link carries that menu.

The Editor tab is one screen and the window of the menu never leaves it:

| Where | What |
|---|---|
| Toolbar above the window | The title, the inventory type (a dropdown) and the size, then a button for each group of the menu's properties (Opening, Events, Layout, Pages, Animation, and More for the rest) that opens a panel of its fields, and the GUI scale. A group with properties set shows how many. |
| List on the left | Every item of the menu with its cell id, its picture, its slots and its material. Click one to edit it, **+ Add** puts a new one in the first free slot. |
| The window | Click a slot to pick the item in it (or add one there), drag an item to another slot to move it. Drag the empty space around it (or use the middle button, or Space and the left button, or the wheel) to move the window, and double click the space or press **Center** to put it back. |
| Panel on the right | The id of the item (rename it here: the legend follows), **Copy**, **Delete**, and every property of the item. |
| Bar under the window | Shown when a resource pack is loaded, the server has glyphs, or the menu has custom items (`nexo:ruby`). Pick a glyph from the strip (search by name or code, a font at a time), see it large, and put it in the title at an offset; the window shows it at once. |

**Paged menus.** The **Pages** button makes the menu paged and opens its panel: the catalog (a dropdown of the plugin's and of the server's plugins' catalogs, with
its settings), the slots of the elements, the order, and buttons for the two items of a paged menu. The item drawn for each element is an entry of the item list ("Element") that is edited like any
item; the values of the catalog are offered above its fields as `{name}` chips (click one to copy it). The window shows the first page: the template is drawn in every slot of the elements, with example values
of the catalog (`Steve`, `Steve2`...), and those slots are outlined. The editor pretends there are a page and a half of elements: the bar under the window turns to the second
page, which is half full, so the items under the elements can be seen. Connected to a server, the window shows what the catalog lists now for you instead (the bar says so,
and how many), with the pages that makes. While the item for a catalog with nothing to list is picked, the window shows that page instead. **Pick the slots in the window**
lets you click slots to put elements in them and take them out (it changes `slots`, or the cells of `layout` if the pages use one). A catalog of another plugin gets a field for each
setting the plugin described (`registerCatalog` with a settings map), and the rest of its keys as JSON.

Other tabs: **Raw** is the menu as text. What is typed there is a draft: **Apply to the editor** or `Ctrl+S` reads it into the editor (`Ctrl+S` then saves, too), and what is changed in the editor is
in the text when the tab is opened. Text that cannot be read is reported as you type and is not applied. **Pack** loads a resource pack. The problems of the menu are under the button at the right of the tabs; click one to go to it.

The entries of a list (lore, actions, conditions) are put in order by dragging the grip (⠿) at their left: the row is inserted where the line shows, and `Alt` + `Up` / `Down` on the grip does the same from the keyboard. A change keeps the scroll of the properties, and a new entry that is an object opens.

The `material` of an item (and of the actions and conditions that name an item) is a search box: type part of a name (`iron sword`, `ruby`)
and pick from the game's items and, from a server, the items of Nexo and ItemsAdder, each with its picture. Anything typed is kept as it is,
so heads (`head:Steve`), textures and items of plugins that cannot list theirs still work.

`Ctrl+Z`, `Ctrl+Y` undo and redo (in the text of the Raw tab they are the text's own); `Ctrl+S` saves to the server, or downloads the file when there is none.

## Setting it up

### 1. Host the site (once, for the organization)

1. In the repository on GitHub, open Settings, then Pages, and set the source to GitHub Actions. The workflow
   `.github/workflows/editor.yml` publishes `editor/web` on each push to `main` that changes it.
2. Under Custom domain, enter `menu.xali.to` and save. The file `editor/web/CNAME` already says the same, so a deploy keeps it.
3. At the DNS provider of `xali.to`, add `CNAME menu <organization>.github.io.` (for the Xalitoria organization:
   `xalitoria.github.io.`). Wait until GitHub reports the DNS check as successful, then turn on Enforce HTTPS.

Any other static host works the same way: serve the contents of `editor/web`.

### 2. Turn on the endpoint on a server (optional)

In `plugins/XaliMenu/config.yml`:

```yaml
editor:
  enabled: true
  host: 127.0.0.1
  port: 8123
  public-url: "https://menu-api.example.com"
```

`host` is the address the plugin listens on; `127.0.0.1` only accepts the machine itself. `public-url` is the address the admin's
browser uses to reach it. Run `/xmenu reload`.

The editor page is served over HTTPS, so the browser only talks to an HTTPS endpoint, with one exception: `http://127.0.0.1` and
`http://localhost` are allowed. That makes two ways to run it:

- **The server is your own computer.** Leave the defaults. The link works as it is.
- **The server is elsewhere.** Put a reverse proxy with a certificate in front of the port and set `public-url` to its address.
  With Caddy:

  ```
  menu-api.example.com {
      reverse_proxy 127.0.0.1:8123
  }
  ```

  With nginx, in the `server` block of a site that has a certificate:

  ```
  location / {
      proxy_pass http://127.0.0.1:8123;
      proxy_set_header Host $host;
  }
  ```

  Do not open the port itself in the firewall.

Only requests that come from the editor's site (`editor.site`), `localhost` or `127.0.0.1` are answered, each needs the token of a
link made with `/xmenu edit` by someone with `xalimenu.admin`, and a token ends after `editor.session-minutes` (60). A token in a
link works like a password for that time: do not paste it into a channel that others read.

### 3. Share through a relay (optional)

For a server that cannot be reached from the editor's page (no open port, behind a proxy you do not control, or a link to give to others),
`/xmenu share [menu]` prints a link that works from anywhere. The plugin only connects out, to a relay such as the one in `menu/relay`
(`node relay.js`, no dependencies; put it behind https, since the page is served over https). Set it in `config.yml`:

  ```yaml
  editor:
    relay:
      url: https://relay.example.com
      token: ""   # only if the relay was started with XM_RELAY_TOKEN
  ```

How it is kept safe:

- The link is `#relay=<relay>&session=<id>&key=<key>`. The key (AES-256-GCM) is after the `#`, so it never reaches the relay or any
  server; the plugin and the page encrypt every request and answer with it. The relay holds a request only until the plugin has answered
  it, writes nothing to a disk, and forgets a session when the plugin stops asking for work or `editor.session-minutes` is over.
- The plugin drops a request that was not made with the key, is more than ten minutes old, or has been seen before.
- **Several people can use one link.** They all see the same menus. What they save is not written: it is checked, then held, and an admin
  runs `/xmenu edits` to list the edits, `/xmenu edits apply <id>` to write one (as a save of the editor does, with the old file kept) or
  `/xmenu edits drop <id>`. A person's name is whatever they typed, so it only helps you tell edits apart. A later save of the same menu
  by the same name replaces the earlier one, and an edit applied over a menu that changed meanwhile replaces it as it is (last write wins).
- A menu can run console commands, so a shared link must be treated as a way to ask for that: never apply an edit you have not looked at.
  `/xmenu share stop` ends your links.
- `/xmenu share readonly [menu]` makes a link that can only read: the plugin refuses a save from it (403) and the page hides its save button.
- What the link can read: the menus, the catalogs the server lists (the preview of a catalog is made for the admin who made the link, so
  an `inventory` catalog shows the admin's items), and the resource pack, which is read in parts (see below).

### 4. Show the resource pack (optional)

`editor.pack` can be the path of a zip. Left empty, the plugin looks for the pack of Nexo (`plugins/Nexo/pack/pack.zip`), Oraxen
(`plugins/Oraxen/pack/pack.zip`) and ItemsAdder (`plugins/ItemsAdder/output/generated.zip`, where it usually writes it; set `editor.pack` if yours is elsewhere), in that order. The Pack tab can also load a
zip from the disk or from an address that allows other sites to read it.

With Nexo or Oraxen installed, the editor lists the names in their `glyphs` folders (with ItemsAdder, the `font_images` of the files in `plugins/ItemsAdder/contents`, each character asked of ItemsAdder), and a title can use `<glyph:name>` in the
preview. Custom items (`nexo:ruby`, `itemsadder:ns:id`) are looked up through the plugins, so their pictures come from the pack too (for ItemsAdder, its own item model when the item carries none). A pack ItemsAdder hosts is downloaded from the address it gives (`ItemsAdder.getPackUrl`), like Nexo's.
A glyph that is moved is written as the space characters of the pack's own font and, for a glyph of another font, a `<font:...>` tag.

**Where a big pack comes from.** The editor picks the first of these that works:

1. **The host the players download it from.** `editor.pack-url` names it; left empty, the plugin asks Nexo for the address it sends to players
   (`Pack.server.type` S3, LOBFILE, POLYMATH or SELFHOST, so an AWS bucket is downloaded from AWS). The address goes to the editor in `/v1/info` with
   the size of the local file, and the page downloads it itself, so the pack never passes through your server or a relay. The host has to allow
   other sites to read it (a CORS rule on the bucket for `https://menu.xali.to`, allowing `GET`), and an https page cannot read a plain `http`
   address such as a self-hosted one; when that fails, or the size is not the local file's, the next one is used. A presigned S3 address ends
   after `url_expiration`, and Nexo makes a new one each time it is asked.
2. **The server, by parts.** `GET /v1/pack` with a `Range` header returns at most 4 MB of the zip. Through a relay the editor always asks this way:
   it reads the directory at the end of the zip and then fetches 1 MB blocks as it needs a font or a texture, so a 100 MB pack costs a few
   megabytes. Without a `Range` the endpoint sends the whole file, which a relay refuses when it is over 5 MB.

## Development

### Requirements

Node.js 20 or later for the page's tests, and the workspace's Gradle for the schema. The page has no build and no
dependencies (`web/src/vendor` holds what it bundles).

### Building and testing

```console
user $ python3 -m http.server -d menu/editor/web 8765          # serve the site, then open http://127.0.0.1:8765
user $ node --test menu/editor/web/test/                       # the tests of the page
user $ ./gradlew :menu:editor:schema :menu:editor:test         # write schema.json, and check that it is up to date
```

> [!TIP]
> A browser caches ES modules and CSS across reloads. Serve the folder with a `Cache-Control: no-store` header, or from a
> new port each time, when a change does not show.

Run the schema task after a change to the menu model, a built-in type, or a comment in the sources. To try the endpoint against a real
server, see `menu/live/README.md` (`XM_EDITOR=1`).

### Source layout

| Where | Holds |
|---|---|
| `menu/editor/kotlin/com/xalitoria/menu/editor` | `DumpSchema.kt` (the entry of the `schema` task), `SchemaBuilder.kt` (walks the serializer descriptors of the model and the plugin) and `SourceDocs.kt` (reads the KDoc comments for the help texts). |
| `menu/editor/test/SchemaTest.kt` | Fails when `web/schema.json` is older than the code. |
| `web/schema.json` | Written by the `schema` task. Do not edit it. It holds the plugin's version as well. |
| `web/src` | The page. Each file starts with a comment about what it does. |
| `web/test` | The page's tests, run with `node --test`. |
| `tools/named-colors.py` | Writes the table of named colors. |

In `web/src`: `app.js` starts the page, `form.js` draws the form from the schema, `store.js` holds the menu with undo, `check.js`
finds problems, `hocon.js` and `hoconWriter.js` read and write menu files, `editorTab.js`, `rawTab.js`, `pagesPanel.js` and
`glyphbar.js` are the panels, `remote.js` and `relay.js` talk to the server's endpoint and the relay, and `zip.js`, `fonts.js`,
`models.js`, `itemdef.js`, `itemrender.js`, `pack.js` and `glyphs.js` read resource packs and draw items. The pieces that need no
browser have tests.

**Adding a field to the form**

1. Add the property to the model in `menu/api` (or the action or condition in `menu/paper`) with a KDoc comment: the comment is the help.
2. Run `./gradlew :menu:editor:schema`, and commit the changed `web/schema.json` with the change.
3. If the editor needs more than a generic field, add it in `form.js` and a test in `web/test`.

**Changing the plugin's endpoint**

The server side is in `menu/paper/.../editor`. Check it live with `menu/live/bot/editor.js` (`XM_EDITOR=1`), see
[Live tests](../live/README.md).

## See also

- [XaliMenu](../README.md#web-editor) — the plugin's side: `/xmenu edit`, `/xmenu share`, `editor.*` in `config.yml`
- [Relay](../relay/README.md) — the mailbox for servers that cannot be reached from outside
- [Live tests](../live/README.md) — trying the endpoint with a bot

## External resources

- [GitHub Pages](https://docs.github.com/pages) — where the site is hosted
