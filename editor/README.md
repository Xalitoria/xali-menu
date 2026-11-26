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
- **A menu reaches the editor in one of three ways.** By default, `/xmenu edit [menu] [--auto-reload]` opens an encrypted session
  through `editor.relay.url` (`https://menu.xali.to/relay`) and prints a short 12-character base64url link
  (`https://menu.xali.to/#<code>`). Both the plugin and browser derive an opaque relay session id and 256-bit AES-GCM key from the
  fragment via PBKDF2-HMAC-SHA256, so neither the static host nor the relay ever sees the code, key, menus, or Nexo/pack data. With
  `editor.enabled` in `config.yml`, the plugin listens on a port and the browser talks to it directly instead. With both off, the
  link carries one menu compressed after `#` and the editor downloads a `.conf` file.
- **Saving is checked twice and waits for reload by default.** The editor reports what it can see; the plugin reads the menu as it
  would at startup before it replaces the file, and refuses one that would not load. The old file is kept as `<name>.conf.bak`. By
  default, saved changes are written to disk and take effect when you run `/xmenu reload [menu]`; pass `--auto-reload` to
  `/xmenu edit` to reload each changed menu immediately on save.
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
- **The player's inventory** is part of the window, under the menu, and a menu can draw in it: click one of its slots and the item is saved with the slot numbers of the
  game (0 to 8 the hotbar, 9 to 35 the rows above it). An item is defined once and used anywhere in the window: its `slot` and `slots` are the slots of the menu and
  its `inventory-slots` those of the player's inventory, so the same item can be in the menu and in the rows under it. An item of the
  menu with a `slot` past the menu's size is shown in the inventory marked in red, and reported as a problem: it is never drawn there.
- **Using an item in more slots.** Drag an item from one slot to another to move it (hold Alt or Ctrl to draw it in both), or drag it from the list onto any slot of the
  window, the menu's or the inventory's. Or pick the item and press **Place in more slots**: every click on a slot then draws the item there, or takes it out if it is there.
  The right panel lists where the item is drawn as chips (click one to see the slot, `×` to take the item out of it); the slots the menu matrix gives it are shown but are the
  matrix's to change. An empty slot offers **+ New item here** and the items you already have. `src/placement.js` has the rules and `test/placement.test.js` its tests.
- **The window** fills the space between the toolbars and is always on the screen. It takes the biggest GUI scale at which it fits (or the scale you chose) and
  the rest of the space is room around it, for glyphs that are used as overlays and reach past the window. It is the game's container texture for the inventory
  type (chest, hopper, dispenser, dropper), the title is in the game's font with the glyphs of the resource pack, and the player's inventory is below it. Counts
  and the durability bar are drawn as the game draws them, and the tooltip uses the game's own sprites, with the name, enchantments and lore. Inline formatted
  field previews (names, lore, titles, messages), written book pages (wrapped to Minecraft's 114px page width), and Bedrock form previews also render in
  Minecraft's bitmap font (`src/textpreview.js`) with loaded pack glyphs and shifts.
- **The game's files** are read from a public copy of Mojang's assets (`editor/web/src/assets.js` names it and the version) and are not
  part of this repository. Everything is fetched the first time it is needed, so the first draw of a menu takes a moment.
- **Player heads.** `skull` (`player_head` with a player name, `textures.minecraft.net` URL, or base64 texture), `material = "head:<player>"` / `texture:<base64>`, and bracketed `[minecraft:profile={name:"..."}]` in `nbt` render the real player skin in the preview: connected to a server they resolve through `/v1/skin?player=<name>` (including online players and SkinsRestorer), and standalone through `mc-heads.net`.
- Not exact: the shimmer of an enchanted item (it moves in the game; one moment of it is shown).
- Text the game's own pictures lack is drawn with its fallback font, Unifont (`src/unihex.js`: the `unihex` providers, half size, `size_overrides`), read
  once and only when such a character appears. The zip files come from the public copy when it has them, else (connected to a server) from
  `GET /v1/game-file?name=font/unifont.zip`, which downloads the file from Mojang's servers for the server's version, checks its hash, and keeps it in
  `plugins/XaliMenu/cache/game`. It will fetch no file but a zip of the game's `font` folder.

## Using it

Run `/xmenu edit` in the game or console, and click the 12-character link (`https://menu.xali.to/#<code>`). Every menu in
`plugins/XaliMenu/menus/` is available in the top menu switcher; `/xmenu edit <menu>` opens that menu first while still letting you
switch to any other menu. Saved changes sync securely to disk and apply after `/xmenu reload` (or pass `/xmenu edit --auto-reload`
to reload changed menus automatically when saved). Run `/xmenu edit stop` to close active edit links early. Without a relay or
direct HTTP endpoint, `/xmenu edit <menu>` is needed since the offline `#data=...` link carries one menu.

The Editor tab is one screen and the window of the menu never leaves it:

| Where | What |
|---|---|
| Toolbar above the window | The title, the inventory type (a dropdown) and the size, then a button for each group of the menu's properties (Opening, Events, Matrix, Pages, Animation, Bedrock, and More for the rest) that opens a panel of its fields, the device preview switcher, and the GUI scale. A group with properties set shows how many. Collapses behind **Settings ▾** on small screens. |
| List on the left | Every item of the menu with its cell id, its picture, its slots (of the menu and of the inventory) and its material. Click one to edit it, drag it onto a slot to use it there, **+ Add** puts a new one in the first free slot. Slides out as a drawer on medium screens (`821px–1180px`) and switches via the top segmented bar on phones (`<= 820px`). |
| The window | Click a slot to pick the item in it (or add one there, or use one you have), drag an item to another slot to move it (Alt: copy). Drag the empty space around it (or use the middle button, or Space and the left button, or the wheel) to move the window, and double click the space or press **Center** to put it back. |
| Panel on the right | The item icon preview, its id (rename it here: `matrix.map` follows), **Copy**, **Delete**, a **Safe / Unsafe mode** toggle, quick-add chips for material-specific meta, and the item's properties. In **Safe mode** (default), only meta applicable to the item's material (or the underlying vanilla item of a server-resolved `itemsadder:` / `nexo:` item) is offered in **+ Add property** (grouped by category), while any already-set non-applicable keys stay untouched and are listed in a *Preserved* notice. Turning **Unsafe mode** on exposes every schema property; turning it back off never overwrites or removes existing properties. |
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

- **Shareable links and saving by other admins.**
  - **Share link** copies a `#data=...&name=...` URL carrying the current menu (in the fragment, never sent to a static host). Another admin can open that link and either paste their `/xmenu edit` / `/xmenu share` link via **Load link** (keeping the shared menu loaded so they can save it straight to the server), or open their server session first and paste the `#data=...` link into **Load link** to review it with the server's resource pack, glyphs and custom items before clicking **Save to server**.
  - **+ New** (when connected to a server) creates a new menu name to edit and save directly to `plugins/XaliMenu/menus/`.
  - `/xmenu share [readonly] [menu]` works through `editor.relay.url` when configured, or directly through the plugin's HTTP endpoint when `editor.enabled` is on (saves are held in `/xmenu edits` for admin approval).

### 3. Relay setup (`https://menu.xali.to/relay/`)

`/xmenu edit` and `/xmenu share` use `editor.relay.url` (default `https://menu.xali.to/relay`) so servers never need to open an
inbound port. Run `menu/relay/relay.js` (`node relay.js`, no dependencies) behind HTTPS and route `/relay/` on `menu.xali.to` (or
your own domain) to it — `relay.js` accepts both `/s/...` and `/relay/s/...` paths automatically, plus `GET /relay/health`:

```yaml
editor:
  relay:
    url: https://menu.xali.to/relay
    token: ""   # only if the relay was started with XM_RELAY_TOKEN
```

How it is kept safe:

- The short link is `https://menu.xali.to/#<code>` (12 URL-safe base64 characters = 72 bits; custom relays append `&relay=...`). The
  fragment never leaves the browser. Both ends derive a 22-character opaque session id and a 256-bit AES-GCM key via
  PBKDF2-HMAC-SHA256 (100,000 iterations), so the relay only sees an opaque session id and encrypted bytes, holds nothing on disk,
  and drops sessions when the plugin stops polling or `editor.session-minutes` expires.
- The plugin drops any request that was not encrypted with the session key, is more than two minutes old, exceeds the body limit, or has a repeated id. The editor page also enforces a strict Content-Security-Policy and clears secret session codes/tokens from the browser URL bar after connecting.
- **Upfront & on-save IP verification (`editor.verify-ip`, default `admin`):** The server checks the browser's IP (via Cloudflare's `CF-Connecting-IP` on the relay, or socket / `editor.trust-proxy` on direct HTTP) both when the editor loads (showing an immediate warning banner before edits are made) and again on save. In `admin` mode, saves from the creator's IP or any currently online `xalimenu.admin` player write directly (supporting console `/xmenu edit` and multi-admin collaboration); saves from unrecognized IPs are automatically held in `/xmenu edits` with client IP and privileged-action inspection (`/xmenu edits inspect <id>`).
- **Live presence & pack reloads:** Open tabs share Google Docs-style collaborator badges and colored slot/item highlights (marking in-game players matched by IP vs anonymous browsers). When Nexo or ItemsAdder reloads its pack, active editor tabs automatically refresh the resource pack, glyphs and custom items without losing unsaved edits.
- **Server-down resilience & cross-server transfer:** If the Minecraft server crashes or restarts while editing, unsaved edits stay safe in memory and `localStorage` draft recovery, and the disconnect banner offers one-click **Reconnect / Save to another server** (also in **File ▾ → Save to another server…**), `.conf` download, or `#data=` link copy. In-game, `/xmenu push <menu> <link-or-code>` pushes a menu directly to another server.
- **Admin edit links (`/xmenu edit [menu] [--auto-reload]`)** read all menus, Nexo/Oraxen/ItemsAdder packs, glyphs, custom items,
  live registries and `config.yml` settings from the server. Saved menus validate and write atomically to `plugins/XaliMenu/menus/`
  without applying live until `/xmenu reload`, unless `--auto-reload` was passed. `/xmenu edit stop` ends active edit links.
- **Shared links (`/xmenu share [readonly|stop] [menu]`)** let collaborators read the same menus and packs (live admin catalog previews `/v1/elements` are disabled on shared links), and every save is held
  in `/xmenu edits` with privileged-action warnings (`console`, `permission`, `group`, `meta`, `gamemode`) until an admin runs `/xmenu edits inspect <id>` and `apply <id>` or `drop <id>`. `/xmenu share readonly [menu]` refuses saves (403) and hides the save button.

### 4. Show the resource pack (optional)

`editor.pack` can be the path of a zip. Left empty, the plugin looks for the pack of Nexo (`plugins/Nexo/pack/pack.zip`), Oraxen
(`plugins/Oraxen/pack/pack.zip`) and ItemsAdder (`plugins/ItemsAdder/output/generated.zip`, `plugins/ItemsAdder/output/pack.zip`, or `plugins/ItemsAdder/data/resource_pack/generated.zip`; set `editor.pack` if yours is elsewhere), in that order. The Pack tab can also load a
zip from the disk or from an address that allows other sites to read it.

With Nexo or Oraxen installed, the editor lists the names in their `glyphs` folders (with ItemsAdder, the `font_images` of the files in `plugins/ItemsAdder/contents`, each character asked of ItemsAdder), and a title can use `<glyph:name>`, `:name:`, `:namespace:id:`, `:offset_N:`, or `%img_offset_N%` in the
preview and in game. Custom items (`nexo:ruby`, `itemsadder:ns:id`, `ia:ns:id`) are looked up through the plugins, so their pictures come from the pack too (for ItemsAdder, its own item model when the item carries none). A pack ItemsAdder hosts is downloaded from the address it gives (`ItemsAdder.getPackUrl`), like Nexo's.
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
| `menu/editor/kotlin/com/xalitoria/menu/editor` | `DumpSchema.kt` (the entry of the `schema` task), `SchemaBuilder.kt` (walks the serializer descriptors of the model and the plugin), `SchemaModel.kt` (the classes `schema.json` is written from and read back into, with `kind` as the discriminator of a type) and `SourceDocs.kt` (reads the KDoc comments for the help texts). |
| `menu/editor/test/SchemaTest.kt` | Fails when `web/schema.json` is older than the code. |
| `web/schema.json` | Written by the `schema` task. Do not edit it. It holds the plugin's version as well. |
| `tools/reference.mjs`, `web/src/reference.js` | Write `../REFERENCE.md`, the page of every key, from `schema.json` (`node tools/reference.mjs`). `reference.test.js` fails when the page is out of date. |
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

**Mistakes.** The problems list says what the plugin says, in the same words (`src/check.js` and `src/suggest.js` follow `ConfigChecker` and `Suggest` of the plugin): a key that nothing reads with
the key it was probably meant to be, a word that is not one of an enum or of the types of an action, a number outside its limits (the boxes of numbers have the same `min` and `max`, which come from
`@Within` in the model through `schema.json`), a time the plugin cannot read. A key the schema does not know is shown as JSON with a **Rename to …** button when it is close to one. A menu is written
the one way when it comes in (`src/normalize.js`: one value for a list becomes a list, an enum gets its case, a number as text becomes a number), so the rest of the editor never meets the easy forms. A `material` that is not an item of the game, or that the server's plugin (Nexo, ItemsAdder...) does not have, is a problem with the item it was probably meant to be, the same words the plugin writes in its log when it draws that item as a barrier; heads, placeholders and sources the editor has no list of are left alone, and so is everything when the list could not be read.

**Values from the game's registries and from placeholders.**
- A key that names something of the game is a dropdown of what the game has (the enchantments of `enchants` and `stored-enchants`, the effect of a potion effect, the
  attribute and its slot, the dye color and pattern of a banner, `potion-type`, the pattern and material of a trim, the slot of `equippable`, the fish, `hide`),
  with **Other…** for a value that is not in the list (another plugin's, a datapack's). Standalone lists come from the language file and curated defaults (`sound`, `particle`, `entity-type`); connected to a server, `GET /v1/registries` upgrades them with the server's live registries (`src/registry.js`).
- A number or a switch that may follow a placeholder (`amount`, `model`, `damage`, `max-stack-size`, `unbreakable`, `glow`, `hide-tooltip`) is one box that takes a number or a
  `%placeholder%`, shown in blue when it is text; a switch is a checkbox with a **%** button that turns it into that box. The picture of an item with a placeholder there is drawn with the default.
- A tipped arrow, potion or splash potion takes the color of its `potion-type` and `potion` effects as it is typed, from the colors of the game's effects (`src/potions.js`);
  a connected server answers with the exact color, and that is believed.
- The player's rows are drawn like any other slot, with `Inventory` and a line under the window saying a click puts an item there.

**Editors made for a kind of value** (`src/editors.js`, which the form uses in place of its generic control):
- `banner` (also what a shield shows): the item as it is drawn with the layers, and for each layer, bottom first, the sixteen dye colors to click, the pattern (a gallery of how each pattern
  looks, a banner each) and buttons to move it and take it out.
- `firework`: the flight, and each burst with its shape (small ball, large ball, star, burst, creeper), its colors and the colors it fades to as chips with the picker of the browser, and
  whether it twinkles or leaves a trail. A firework star in the window takes the average color of its first burst.
- `potion` (custom effects): a row for each effect with the effect from the game's list, its length in **seconds** (the file has ticks; half a second is 10) and its **level** (the file has the level minus one),
  with the level in numerals and the length as the game's tooltip shows them (`III for 1:30`); the potion in the window changes its color as effects are added.
- `book` pages (a written book, and the `book` action): each page is a box beside the page as the reader sees it, with its colors, a count of its characters, and buttons to move it and take it out.
- `food`: the food bar as the player sees it (drumsticks, two points each) with the nutrition, the saturation (left empty it follows the nutrition) and whether it can be eaten when full.
- `tool`: the speed and wear of the tool, and a card for each rule with its blocks as chips, the usual tags (pickaxe, axe, shovel, hoe, sword) to add at a click, the speed and whether they drop.
- `trim` (armor): the armor **as it is worn**, from the front, with each of the patterns (a gallery), and the piece in each material (another gallery), so that a pattern is chosen by how it looks. The worn picture (`src/armor.js`) is made as the game makes it: the armor texture of the equipment asset (`equipment/<id>.json`, with leather dyed by `color`), the part of the figure that the slot covers, and the trim texture over it, which is the pattern in the colors of the material (the atlas's `paletted_permutations`, with the darker shade for armor of the same material). The icon of the item in a window is made from its item definition, whose `trim_material` choice picks the model; neither has the pattern in it where the game does not. Everything is read from the pack first and the game second, so a pack that changes an armor texture, a trim pattern, a palette, an equipment asset or an item model changes the pictures too.
- `fish` (a bucket of tropical fish): the bucket, the pattern from the game's list and the two dyes as colored buttons.
- `matrix` (the global Matrix group): the menu matrix (`matrix.cells` and `matrix.map`) as a **picture**. The grid of the window (nine columns, a row for each row of the menu) where a cell shows the item its character stands for (highlighting any cell also claimed by another item's explicit `slot`/`slots`); a palette above it lists `matrix.map`, each character as its item with the item to change and a × to take it out, an eraser, and a row to add a character for an item. Choose a character, then click or drag across cells to paint them (a right click erases). The grid shows the painting while the pointer is down and the menu is written when it is let go, so the window behind is drawn once. A character in the grid that `matrix.map` does not know is shown as itself and told to be nothing. `src/layout.js` has the rules and `test/layout.test.js` its test.
- **Sentences to edit through** (`src/inline.js`): the most used actions and conditions (about fifty) are a line of words with the boxes in it, `Tells the player [text]`, `[Gives|Takes] the permission [node]`, `After [40] ticks, runs:`, and the keys in the sentence are edited there, with the same controls as the form (a dropdown for an enum or a registry, a box for a number). The keys the sentence does not hold stay in the form under it, so nothing is hidden; a switch that is back at its default is taken out of the file, not written. A type with no sentence has only the form. A test makes sure that every key a sentence holds is a key of its type.
- **Sentences** (`src/sentences.js`): an action or a condition is said in words under its type (`After 2 seconds: Closes the menu`, `The player has the permission shop.use`), which follows what is typed, and the same words are what a folded row of a list shows, a check of a gate by its condition. A type the page does not know (an addon's) shows its name. A test makes sure that every action and condition of the schema has a sentence of its own.
- `attributes`: for each modifier the attribute, what it does (add, add a share of the base, multiply the total) with the amount's effect in words (`+50% of the base`, `×1.5`), and the slot the item must be in.
- `color` (leather armor and the like): the picker of the browser and the `#rrggbb` text.
