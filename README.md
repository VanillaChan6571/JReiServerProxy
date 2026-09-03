# JReiProxyServer

JReiProxyServer is a Paper plugin that gives JEI and REI clients the server half those mods expect but a plugin server cannot provide: the server's recipes, and answers to the mods' own packets.

Since Minecraft 1.21.2 the vanilla server no longer sends recipe data to clients. On a plugin server that leaves a recipe viewer showing the recipes shipped with the client, and JEI says so:

> This server does not provide recipes to JEI. JEI is showing default recipes from your client...

This plugin sends them the way a mod loader would. No client mod, no mod loader on the server.

## Requirements

- Paper, Purpur, Folia or a fork on Minecraft 26.2. The plugin is compiled against that version's server internals and will not load on another.
- A Fabric or NeoForge client with JEI or REI, as your players already have. Fabric clients need Minecraft 1.21.10 or newer — Fabric API's recipe-sync channel does not exist below that.

## What it does

**Recipe sync.** The server's recipes are encoded once and sent to each player on whichever channel their client can read: `fabric:recipe_sync` for Fabric, `neoforge:recipe_content` for NeoForge. REI normally builds its list from the recipe book. When full REI cheat channels are enabled, REI deliberately ignores recipe-book packets, so the plugin sends the same recipes through REI 26.2's version-pinned `sync_displays` protocol instead.

JEI builds its list the moment the server's own recipe packet arrives, which is before any plugin can send anything, so it prints its warning once and then reloads with the server's recipes a moment later. The warning is unavoidable and does not mean the sync failed; `recipe-sync.notify-player` follows it with a line saying so.

**Cheat mode.** Pulling an item out of the list, deleting the held item, and setting a hotbar slot. Fully available for JEI and REI. REI's full cheat trio is paired with its display sync, preserving complete item components such as a light block's level without losing server recipes. If that display list cannot be encoded completely, the plugin automatically keeps the trio disabled and leaves REI on its recipe-safe hotbar and `/give` fallback. Cheat packets handled by the plugin are gated on `jreiproxyserver.cheat`.

**Recipe transfer.** The "+" button that fills a crafting grid from the player's inventory, for both JEI (`recipe_transfer`, `recipe_transfer_counted`) and REI (`move_items_new`). Each mod's own server-side algorithm is reimplemented rather than approximated: the client decides which slots to use, but the server picks the items and moves them, so a stale client view cannot duplicate or void anything. REI decides whether to offer the button purely from whether the server registered its channel, so it is only advertised while `recipe-transfer.enabled` is on.

**Recipe blacklist.** Recipes listed in the config are left out of everything the plugin sends.

## Versioning

The version is the Minecraft version this is built for, plus a plugin revision: `26.2.1` adds REI 26.2 display sync and full packet cheat mode. The jar is compiled against that version's server internals and will not load on another, so the version number is also the compatibility statement.

## Commands

Anyone:

- `/jei` (or `/rei`, optionally with `resync` / `retry`) — ask the server to send the recipes again. Useful when a slow login lost them; rate limited by `recipe-sync.player-resync-cooldown-seconds`.

Operators:

- `/jreiproxy reload` — reloads the config and re-encodes the recipes.
- `/jreiproxy resync <player|all>` — resends the recipes to someone else.
- `/jreiproxy info` — recipe counts, payload sizes and how many players have been synced on each channel.

`/jeiproxy` and `/jrp` are aliases of `/jreiproxy`.

## Permissions

- `jreiproxyserver.admin` — the operator commands above, and exemption from the `/jei` cooldown. Default OP.
- `jreiproxyserver.cheat` — cheat mode in JEI and REI. Default OP.

## Configuration

`plugins/JReiProxyServer/config.yml`:

| Key | |
| --- | --- |
| `language` | `en` or `zh_cn`, or the name of a file you add under `lang/`. |
| `recipe-sync.enabled` | Master switch. Off means JEI shows the client's own recipes. |
| `recipe-sync.on-join` | Send recipes when a player joins. |
| `recipe-sync.on-datapack-reload` | Re-encode and resend after `/reload`. |
| `recipe-sync.recipe-book` | Who gets the full recipe book: `auto` (clients reporting REI), `all`, or `off`. |
| `recipe-sync.trigger-recipe-update` | Send the packet that makes an already-loaded viewer re-read the recipes. Off means recipes still arrive but JEI keeps showing client defaults. |
| `recipe-sync.strip-crafting-requirements` | Leave crafting requirements out of recipe-book entries. Avoids a client being dropped over an unresolvable item tag, at the cost of the vanilla recipe book treating every recipe as uncraftable and drawing its ingredients blank. Off by default; only worth turning on if clients are actually being kicked. |
| `recipe-sync.player-resync-cooldown-seconds` | Seconds a player must wait between `/jei` resyncs. `0` disables the limit. |
| `recipe-sync.notify-player` | Tell the player in chat once the recipes have arrived, so JEI's earlier warning reads as out of date. Wording lives in `lang/`. |
| `cheat-mode.enabled` | Master switch for cheat mode. |
| `cheat-mode.rei-channels` | Enable the three full REI cheat channels with the matching `sync_displays` recipe path. On by default and requires recipe sync plus a complete display payload. If encoding is incomplete, the plugin automatically keeps the trio off; transfer, hotbar cheat, and REI's component-losing `/give` fallback remain available. JEI is unaffected. |
| `cheat-mode.allow-creative` | Also let any creative-mode player cheat, regardless of permission. |
| `recipe-transfer.enabled` | Master switch for the "+" button. |
| `recipe-blacklist` | Recipe ids (`namespace:key`) to leave out of everything sent. |
| `debug` | Log per-player sync details. |

Keys added by a plugin update are written into your existing `config.yml` and `lang/*.yml` on startup, with their comments. Anything you changed is left alone, and a message missing from your language file falls back to the copy inside the jar rather than appearing as a raw key.

### Folia

Declared `folia-supported`. Per-player work is scheduled on the region thread owning that player
rather than on a main thread that does not exist there, the recipe data is published as a single
immutable snapshot so a datapack reload cannot be observed half-applied, and the state shared
between players is concurrent.

Tested on an actual Folia server: the code follows Folia's threading rules and runs on Paper API + Folia.

### Notes

Sending the whole recipe book makes the vanilla recipe book list everything as known. Nothing is unlocked server-side and it is not an exploit — the server still checks what a player knows when they click a recipe. REI clients using display sync do not need this recipe-book workaround.

Recipes carry the server's internal numeric registry ids, which change with every Minecraft version. A player on a different version than the server cannot decode them. If you run ViaVersion, expect recipe sync to work only for players on the server's own version.

REI's packets travel inside Architectury's split-packet framing and byte-array envelope. Client-to-server packets split above roughly 32 KB; server-to-client packets such as `sync_displays` split above roughly 1 MiB. The plugin handles both directions.

The Fabric payload only carries recipes whose serializer is in the `minecraft` namespace. Fabric's decoder discards the entire payload on meeting a serializer the client did not opt into, and that opt-in list arrives in the configuration phase, which a plugin cannot see. Anything skipped is reported in the startup log.

## Licence

MIT — see [`LICENSE`](LICENSE).

The recipe-transfer handling is ported from JEI and REI, both MIT licensed, and the released jar
bundles the Kotlin standard library under Apache 2.0. Those notices are in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

Unofficial and third party. Not affiliated with or endorsed by the authors of JEI, REI, Fabric,
NeoForge or Architectury.

## Building

JDK 25 and an internet connection. The build downloads a Paper development bundle on first run, which takes a while.

```bash
./gradlew build
```

The jar lands in `build/libs`.
