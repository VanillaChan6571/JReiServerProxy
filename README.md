# JReiProxyServer

JReiProxyServer is a Paper plugin that gives JEI and REI clients the server half those mods expect but a plugin server cannot provide: the server's recipes, and answers to the mods' own packets.

Since Minecraft 1.21.2 the vanilla server no longer sends recipe data to clients. On a plugin server that leaves a recipe viewer showing the recipes shipped with the client, and JEI says so:

> This server does not provide recipes to JEI. JEI is showing default recipes from your client...

This plugin sends them the way a mod loader would. No client mod, no mod loader on the server.

## Requirements

- Paper, Purpur, Folia or a fork on Minecraft **26.2** or **1.21.11**. Take the jar matching your server: the plugin is compiled against that version's server internals and will not run on the other one.
- A Fabric or NeoForge client with JEI or REI, as your players already have. Fabric clients need Minecraft 1.21.10 or newer — Fabric API's recipe-sync channel does not exist below that.

## What it does

**Recipe sync.** The server's recipes are encoded once and sent to each player on whichever channel their client can read: `fabric:recipe_sync` for Fabric, `neoforge:recipe_content` for NeoForge. REI ignores both and builds its list from the recipe book, so REI clients are sent the full recipe book instead.

JEI builds its list the moment the server's own recipe packet arrives, which is before any plugin can send anything, so it prints its warning once and then reloads with the server's recipes a moment later. The warning is unavoidable and does not mean the sync failed; `recipe-sync.notify-player` follows it with a line saying so.

**Cheat mode.** Pulling an item out of the list, deleting the held item, and setting a hotbar slot. Fully available for JEI. For REI, the hotbar and transfer channels are advertised by default because REI gates them on their own, while the three channels that would make REI discard the server's recipes are opt-in (see `cheat-mode.rei-channels`). Gated on the `jreiproxyserver.cheat` permission, and reported back to the client so JEI's own refusal message is accurate.

**Recipe transfer.** The "+" button that fills a crafting grid from the player's inventory, for both JEI (`recipe_transfer`, `recipe_transfer_counted`) and REI (`move_items_new`). Each mod's own server-side algorithm is reimplemented rather than approximated: the client decides which slots to use, but the server picks the items and moves them, so a stale client view cannot duplicate or void anything. REI decides whether to offer the button purely from whether the server registered its channel, so it is only advertised while `recipe-transfer.enabled` is on.

**Recipe blacklist.** Recipes listed in the config are left out of everything the plugin sends.

## Versioning

The version is the Minecraft version this is built for, plus a plugin revision: `26.2.0` is the first release for Minecraft 26.2, `1.21.11.0` the first for 1.21.11. The version number is therefore also the compatibility statement.

There is one jar per Minecraft version because there has to be. The same source compiles against
both, but the server classes it encodes recipes through change shape between releases —
`RecipeSerializer` is a class on 26.2 and an interface on 1.21.11 — so a jar built for one dies with
an `IncompatibleClassChangeError` on the other. 1.21.11 matters because that is Folia's stable line;
Folia 26.2 exists only as a beta.

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
| `cheat-mode.rei-channels` | Advertise the three REI cheat channels that make REI ignore the server's recipes. Off by default: REI treats a server accepting all three as running REI itself and discards the recipe book it is sent. With it off, REI recipe transfer and hotbar cheat still work, and operators still get REI's give through its `/give` fallback; only deleting the held item is lost. JEI is unaffected. |
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

Untested on an actual Folia server: the code follows Folia's threading rules and runs on Paper, but
nobody has yet run it under regionised threading.

### Notes

Sending the whole recipe book makes the vanilla recipe book list everything as known. Nothing is unlocked server-side and it is not an exploit — the server still checks what a player knows when they click a recipe.

Recipes carry the server's internal numeric registry ids, which change with every Minecraft version. A player on a different version than the server cannot decode them. If you run ViaVersion, expect recipe sync to work only for players on the server's own version.

REI's packets travel inside Architectury's split-packet framing, which prefixes a state byte to every payload and splits anything over 32 KB across several messages. The plugin unwraps and reassembles that before reading any REI packet.

The Fabric payload only carries recipes whose serializer is in the `minecraft` namespace. Fabric's decoder discards the entire payload on meeting a serializer the client did not opt into, and that opt-in list arrives in the configuration phase, which a plugin cannot see. Anything skipped is reported in the startup log.

## Licence

MIT — see [`LICENSE`](LICENSE).

The recipe-transfer handling is ported from JEI and REI, both MIT licensed, and the released jar
bundles the Kotlin standard library under Apache 2.0. Those notices are in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

Unofficial and third party. Not affiliated with or endorsed by the authors of JEI, REI, Fabric,
NeoForge or Architectury.

## Building

An internet connection, and a JDK matching the target (below). The first build of each target
downloads and decompiles a Paper development bundle, which takes a while.

```bash
./gradlew build                      # 26.2, the default
./gradlew build -Pminecraft=1.21.11  # the Folia-stable target
```

The jar lands in `build/libs`. JDK 25 is needed for the 26.2 target; the 1.21.11 target builds on
21. The build downloads a Paper development bundle for whichever target on first run.
