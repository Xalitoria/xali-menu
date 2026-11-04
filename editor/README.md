# XaliMenu editor

A web editor for XaliMenu's menu files, hosted at <https://menu.xali.to>. It edits the same files the plugin reads: it shows each menu
as a form (every action, condition, activator and item property, with the comments of the source as help), as the window with its
items, and as text. It checks a menu while you type, shows the glyphs and custom items of the server's resource pack, and moves
glyphs in titles into place.

## How it works

The editor is static files: HTML, CSS and JavaScript with no build step and no dependencies. GitHub Pages serves them, and nothing
else is hosted. There is no relay and no database. In particular:

- **The form is generated from the plugin.** `web/schema.json` is written by `./gradlew :menu:editor:schema` from the serializer
  descriptors that read the menu files, and the comments of the Kotlin sources. `./gradlew :menu:editor:test` fails when the file
  is older than the code, so the editor cannot drift from the plugin.
- **A menu reaches the editor in one of two ways.** With `editor.enabled` in `config.yml`, the plugin listens on a port and the
  browser talks to it directly (the link from `/xmenu edit` holds a token). Without it, the link holds the menu itself, compressed,
  after the `#`; the browser never sends that part to any server. The editor then gives a `.conf` file to put in the menus folder.
- **Saving is checked twice.** The editor reports what it can see; the plugin reads the menu as it would at startup before it
  replaces the file, and refuses one that would not load. The old file is kept as `<name>.conf.bak`.
- **The resource pack stays in the browser.** The server sends its pack (or you pick the zip), the page reads the fonts and
  textures out of it, and nothing is stored or uploaded.

LuckPerms' editor works through two services run for it (a paste service and a socket relay). GitHub Pages cannot run services,
so this editor connects the browser to the server itself, or uses a file.

## How items and windows are drawn

The editor draws what a player sees, from the same files the game reads, with no model of its own for the common items:

- **Items** are drawn from their item definition (`assets/<namespace>/items/<name>.json`) and model json, found in the resource
  pack first and in the game's own files after it. The `gui` display transform, the model's parents, element rotation, texture
  rotation, tints (dyed leather, grass, potions), `custom_model_data` and `item-model` choices are followed, and the result is drawn
  in software at three pixels per pixel of the game with the game's two item lights and nearest-neighbour textures. Compared
  with icons the game rendered itself (the wiki's), stone, a furnace, stairs and a sword differ in at most a few of about six hundred
  pixels, and a creeper head and a shulker box in none.
- **Drawn from code in the game**, and so built here from boxes with the game's texture layout: player and mob heads (a `texture:`
  material shows its skin, fetched through the server because Mojang's texture host does not allow other sites), chests and shulker
  boxes. Banners, shields, signs, beds drawn by block entities, armor trims and the animation of compasses and clocks are not drawn
  that way yet: they show their base model, or a tile.
- **The window** is the game's container texture for the inventory type (chest, hopper, dispenser, dropper), the title is in the
  game's font with the glyphs of the resource pack, and the player's inventory is below it. Counts and the durability bar are drawn as
  the game draws them, and the tooltip uses the game's own sprites, with the name, enchantments and lore.
- **The game's files** are read from a public copy of Mojang's assets (`editor/web/src/assets.js` names it and the version) and are not
  part of this repository. Everything is fetched the first time it is needed, so the first draw of a menu takes a moment.
- Not exact: the shimmer of an enchanted item (it moves in the game; one moment of it is shown), the colors of potions by effect,
  `head:<name>` heads (they use the default skin; only `texture:` skins are fetched), and text outside the Latin range (the game's
  Unifont is not read).

## Using it

Run `/xmenu edit` in the game or the console, and open the link. `/xmenu edit <menu>` opens one menu first. Without the endpoint,
`/xmenu edit <menu>` is needed, since the link carries that menu.

| Tab | Does |
|---|---|
| Menu | The keys of the menu, except the items. |
| Items | The window with its slots: click a slot, drag an item to another slot, edit it on the right. |
| Raw | The menu as text; Apply reads it back. |
| Pack | Load a resource pack; see its glyphs; align a glyph in the title. |

`Ctrl+Z`, `Ctrl+Y` undo and redo; `Ctrl+S` saves to the server, or downloads the file when there is none.

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

### 3. Show the resource pack (optional)

`editor.pack` can be the path of a zip. Left empty, the plugin looks for the pack of Nexo (`plugins/Nexo/pack/pack.zip`), Oraxen
(`plugins/Oraxen/pack/pack.zip`) and ItemsAdder (`plugins/ItemsAdder/output/generated.zip`, where it usually writes it; set `editor.pack` if yours is elsewhere), in that order. The Pack tab can also load a
zip from the disk or from an address that allows other sites to read it.

With Nexo or Oraxen installed, the editor lists the names in their `glyphs` folders, and a title can use `<glyph:name>` in the
preview. Custom items (`nexo:ruby`, `itemsadder:ns:id`) are looked up through the plugins, so their pictures come from the pack too.
A glyph that is moved is written as the space characters of the pack's own font and, for a glyph of another font, a `<font:...>` tag.

## Development

```console
user $ python3 -m http.server -d menu/editor/web 8765          # serve the site, then open http://127.0.0.1:8765
user $ node --test menu/editor/web/test/                       # the tests of the page
user $ ./gradlew :menu:editor:schema :menu:editor:test         # write schema.json, and check that it is up to date
```

Run the schema task after a change to the menu model, a built-in type, or a comment in the sources. To try the endpoint against a real
server, see `menu/live/README.md` (`XM_EDITOR=1`).

The source is in `web/src`; each file starts with a comment about what it does. `form.js` draws the form from the schema, `store.js`
holds the menu with undo, `check.js` finds problems, `hocon.js` and `hoconWriter.js` read and write menu files, and `zip.js`,
`fonts.js`, `models.js`, `pack.js` and `glyphs.js` read resource packs. The pieces that need no browser have tests.
