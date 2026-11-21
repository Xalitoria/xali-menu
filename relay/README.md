# XaliMenu relay

**The XaliMenu relay** is a small mailbox that lets the web editor at <https://menu.xali.to> reach a server that only makes outbound connections. It is used by both `/xmenu edit` and `/xmenu share`; see "Relay setup" in `../editor/README.md`.

The relay moves encrypted messages. Both the plugin and the browser derive a 22-character opaque session id and a 256-bit AES-GCM key from the 12-character link fragment (`https://menu.xali.to/#<code>`), so the relay never sees the link code, the encryption key, any menu, or any pack data, keeps nothing after a call is answered, and writes nothing to disk. A request waits in memory until the plugin has answered it (30 seconds at most), and a session is forgotten when the plugin stops polling or its time is up (at most four hours).

Both `/s/...` and `/relay/s/...` paths (plus `/health` and `/relay/health`) are accepted out of the box, so reverse-proxying `https://menu.xali.to/relay/` to `relay.js` works with or without stripping the `/relay` prefix.

## Installation

Node.js 20 or later, no dependencies and nothing to build. Copy `relay.js` to the host and run it:

```console
user $ node relay.js
XaliMenu relay on port 8787
```

For a service, run it under the host's init system and put a reverse proxy with https in front of it.

## Configuration

Read from the environment:

| Variable | Default | Meaning |
|---|---|---|
| `PORT`, `HOST` | `8787`, `127.0.0.1` | Where it listens. Put it behind a reverse proxy with https: the editor is served over https and the browser refuses to call plain http (except on localhost). |
| `XM_RELAY_TOKEN` | none | When set, only a request with this bearer token may open a session; put the same value in `editor.relay.token`. Without it anyone can use the relay as a mailbox. |
| `XM_RELAY_ORIGINS` | any | Sites that may call it from a browser, comma separated, such as `https://menu.xali.to`. |

## Development

The protocol is in the comment at the top of `relay.js`. Run the tests from `menu/relay`:

```console
user $ node --test test
```

## See also

- [Web editor](../editor/README.md) — the page and the plugin's side of `/xmenu share`
- [XaliMenu](../README.md) — the plugin
- [Live tests](../live/README.md) — `bot/relay.js` runs the whole path with a bot
