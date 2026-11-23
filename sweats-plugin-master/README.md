# Sweats

A Paper server plugin that bundles the small quality-of-life systems a survival server usually ends up installing five separate plugins for: item sharing, chat mentions, player stats, a per-player scoreboard and tablist, ranks, nicknames, themed leaderboards, AFK tracking, permissions, moderation, admin jumpscares, mapart, cosmetic runes and the Runic Table, Votifier and login protection.

Built for **Paper 26.3** on **Java 25**.

## Features

- **`/show`** — share the item in your hand or an armor slot in chat with a real hoverable tooltip, configurable broadcast radius and cooldown.
- **Chat mentions** — online player names light up inside chat (`Name` and `@Name` both work). Only the name is recolored, so rank prefixes, message colors and other chat plugins keep working.
- **`/scare`** — admin jumpscares built from titles, sounds, particles and post effects. Four presets: creeper, glitch, spider and void.
- **`/gif`** — turn any image dropped into `plugins/Sweats/gifs/` into a mapart item in your inventory.
- **`/stats`** — a 27-slot chest menu with a player's first join, playtime, distance walked, blocks mined/placed, items used, kills and deaths. Works for offline players too.
- **`/ram` and `/ping`** — JVM heap usage and latency with color-coded thresholds.
- **Ranks** — create, delete, assign and inspect ranks. Ranks carry a color and a weight, drive chat prefixes, tablist ordering and the sidebar.
- **Nicknames** — set a display nickname with `/nick`. Nicknames are always shown with a leading `~` in chat, the tab list and the sidebar.
- **Themed leaderboards** — place floating boards with `/leaderboard place <category>` (playtime, deaths, kills, afk, votes, streak). Each has its own colour, symbol, block pedestal and floating icon, shows the viewer's own score at the top and the top ten below, and uses nicknames where they exist. Only confirmed players are ranked — NPC/shop profiles in the server user cache are excluded, while a real player whose name contains "shopkeeper" still counts once they have joined. Offline players stay on the boards, and offline-mode accounts work normally.
- **AFK tracking** — after a configurable idle timeout, each player's AFK time is accumulated into `afk.yml` and shown on the AFK leaderboard. Tracking only: nobody is kicked or moved.
- **Runes and the Runic Table** — 60 Hypixel SkyBlock-style cosmetic runes (Standard, Slayer, Event and Fire Sale) that play particle trails on weapons, bows, armour and boss kills. Open `/runes` to browse the catalog (left/right/shift-click for Tier I–III), and combine two identical runes in the Runic Table to upgrade a tier or apply a rune to matching gear. The Runic Table is a craftable, placeable custom block; bosses drop runes and announce rare finds server-wide. Ported from the standalone SweatyRunes addon.
- **Permissions** — grant and revoke permission nodes per player or per rank, layered on top of Bukkit permissions.
- **Moderation** — `/ban`, `/kick`, `/mute`, `/unban`, `/unmute` and `/history` with temporary or permanent punishments.
- **Scoreboard + tablist** — a compact, per-player SMP sidebar with the configured title, rank, actual team name, Sweat Coins, TryHard Shards, playtime, online/max count and server address. Lines, colors and refresh interval are configurable; players can hide their own sidebar without disabling the tablist. The existing tablist header/footer remains independently configurable.
- **Vote listener** — receives Votifier votes (legacy RSA v1 and token-authenticated v2) directly in Sweats, replacing a separate OyVotifier/NuVotifier jar. Broadcasts each vote, runs configurable reward commands, and persists each player's total votes and highest consecutive-day voting streak for the vote leaderboards.
- **Login gate** — `/login`, `/register`, `/changepass`, `/logout` and `/premium`, backed by SQLite. Unauthenticated players are spawned in a login area *before* the client receives world data, so their real coordinates are never sent until they log in. Existing SimpleLogin BCrypt accounts are imported automatically from an optional `plugins/Sweats/legacy-passwords.db` and then from `plugins/SimpleLogin/passwords.db`. After each password login, players who are not verified premium see a one-click `/premium` tip with an opt-out, and operators can reset a forgotten password with `/changepass <player> <newPassword>`.

## Requirements

| | |
|---|---|
| Server | Paper 26.3+ (uses the Paper API, not Spigot) |
| Java | 25 (enforced by the Gradle toolchain) |
| Build | Gradle 9.x — no wrapper is checked in |

## Build

```bash
gradle build
```

The jar lands in `build/libs/Sweats-1.0.0.jar`.

Run the test suite with:

```bash
gradle test
```

Tests use JUnit 5 and cover the layout, formatting, storage, rank, permission, moderation, scare, auth, leaderboard and command layers.

## Install

1. Build the jar (or grab a release).
2. Drop it into your server's `plugins/` folder.
3. Start the server. `plugins/Sweats/config.yml` is created on first run.
4. Edit the config, then run `/sweats reload` — no restart needed.

For mapart, create `plugins/Sweats/gifs/`, drop images in (`png`, `jpg`, `jpeg`, `gif`, `bmp`), run `/sweats reload`, then use `/gif <file>`.

## Vote listener

Sweats can accept Votifier votes itself. Set `votes.enabled: true`, keep `votes.port` in sync with
what the voting site sends to (default `25536`), and restart. On first start Sweats imports the
token and RSA keys from `plugins/oyvotifier` into `plugins/Sweats/vote/`, so sites such as
Minecraft.Buzz keep working after the standalone plugin is removed. Do not leave both listeners
bound to the same port.

Each vote is logged, broadcast with `votes.broadcast`, and can run any commands in
`votes.rewards` (`{player}` and `{site}` are replaced). Use `/sweats vote status` to inspect the
listener and `/sweats vote test <player> [site]` to simulate a vote.

## Runes and the Runic Table

Sweats bundles the former SweatyRunes addon under the same plugin. There are 60 cosmetic runes in four groups (Standard, Slayer, Event and Fire Sale), each with an equipment type and three tiers. Rune effects are particle-only: they never change combat stats.

- **Browsing** — `/runes` opens the catalog. Left-click a rune for Tier I, right-click for Tier II, shift-click for Tier III. `/runes give <rune> [tier] [player]` gives a specific rune and requires `sweats.runes.admin`.
- **Access** — `/runes` needs `sweats.runes`. Opening the Runic Table with `/runetable` needs `sweats.runetable` **and** either `sweats.runes.admin` or a per-player grant. The same check gates crafting and placing the Rune Table, so a granted player can craft their own. Manage grants with `/runeaccess grant|revoke|check <player>` and `/runeaccess list`.
- **The Runic Table** — craft it from the recipe under `runes.table.recipe` (obsidian, diamond blocks, an eye of ender and an enchanting table by default). Placing it creates a barrier hitbox plus a custom model. Right-click opens the menu; sneak + right-click with an empty hand (or break/hit it) picks it back up. Drop a rune on a matching item to apply it, combine two identical runes of the same tier to reach the next tier, or insert an item that already has a rune on its own to remove it. Inputs are returned when the menu closes.
- **Boss drops** — the Warden, Wither, Ender Dragon and Elder Guardian roll for a rune on death. `runes.drops.chance` sets the COMMON chance in percent, each rarity step is multiplied by `runes.drops.rarity-multiplier`, and `runes.drops.tier-weights` decides the tier. Important drops are announced server-wide. `/runepermdropchance <player> add <points>` adds a permanent bonus (capped by `runes.drops.max-perm-bonus`); the testing-only `/runeluck` multiplier resets on restart.
- **Admin Sword** — `/adminsword` gives a sword that one-taps bosses and plays every weapon rune at once.

### Resource pack

The Rune Table's custom model needs the `SweatyRunes-pack.zip` resource pack (it contains only the table model; rune heads use Mojang texture hashes and need no pack). A copy is checked in at `resourcepack/SweatyRunes-pack.zip`; host it somewhere with a direct download link, then set `runes.resource-pack.url` and the matching `runes.resource-pack.sha1` (compute it with `sha1sum SweatyRunes-pack.zip`). The pack is sent on join; the plugin logs a warning and skips it while either value is empty. Runes and the table still work without the pack, but the custom table model only appears once it is installed. If the server already sends a pack, merge the rune table files into that pack instead of sending two packs.

If an older standalone SweatyRunes plugin left `plugins/SweatyRunes/access.yml`, Sweats imports it once when `plugins/Sweats/runes/access.yml` does not exist yet.

## Commands

| Command | Usage | Permission |
|---------|-------|------------|
| `/show` | `/show [hand\|offhand\|helmet\|chestplate\|leggings\|boots]` | `sweats.show` |
| `/sweats` | `/sweats reload` · `/sweats scoreboard [toggle\|reload\|edit\|settitle\|setline\|reset]` · `/sweats auth <status\|import\|unregister <player>>` · `/sweats vote <status\|test <player> [site]>` | `sweats.admin` / `sweats.scoreboard.toggle` / `sweats.scoreboard.admin` / `sweats.auth.admin` / `sweats.vote.admin` |
| `/scare` | `/scare <player> [jumpscare1\|jumpscare2\|jumpscare3\|jumpscare4]` | `sweats.scare` |
| `/gif` | `/gif [image]` (no argument lists available images) | `sweats.gif` |
| `/stats` | `/stats [player]` | `sweats.stats` |
| `/ram` | `/ram` | `sweats.ram` |
| `/ping` | `/ping [player]` | `sweats.ping` / `sweats.ping.others` |
| `/rank` | `/rank create <name> <color> <weight>` · `delete <name>` · `set <player> <rank>` · `info <rank>` · `list` | `sweats.rank.manage` |
| `/nick` | `/nick <nickname>` · `/nick <player> <nickname>` · `/nick <player> off` | `sweats.nick` |
| `/leaderboard` | `/leaderboard place [category]` · `remove` · `list` · `categories` (alias `/lb`) | `sweats.leaderboard` |
| `/grant` | `/grant user <player> <node>` · `rank <rank> <node>` · `revoke <user\|rank> <target> [node]` · `list <user\|rank> <target>` | `sweats.permission.manage` |
| `/ban` | `/ban <player> <duration> <reason>` | `sweats.banperm` |
| `/kick` | `/kick <player> <reason>` | `sweats.kickperm` |
| `/mute` | `/mute <player> <duration> <reason>` | `sweats.muteperm` |
| `/unban` | `/unban <player>` | `sweats.unbanperm` |
| `/unmute` | `/unmute <player>` | `sweats.unmuteperm` |
| `/history` | `/history <player>` | `sweats.historyperm` |
| `/login` | `/login` (types the password in chat) or `/login <password>` | everyone |
| `/register` | `/register` (password twice in chat) or `/register <password> <password>` | everyone |
| `/logout` | `/logout` | everyone |
| `/changepass` | `/changepass` (old + new password in chat) · `/changepass <player> <newPassword>` | everyone · `sweats.auth.admin` |
| `/premium` | `/premium [status\|check\|mute\|unmute]` | everyone |
| `/runes` | `/runes` · `/runes give <rune> [tier] [player]` | `sweats.runes` / `sweats.runes.admin` |
| `/runetable` | `/runetable` · `/runetable give <player>` | `sweats.runetable` / `sweats.runes.admin` |
| `/adminsword` | `/adminsword` | `sweats.runes.admin` |
| `/runeluck` | `/runeluck <multiplier\|reset>` (testing, resets on restart) | `sweats.runes.admin` |
| `/runeaccess` | `/runeaccess <grant\|revoke\|check> <player>` · `/runeaccess list` | `sweats.runes.admin` |
| `/runepermdropchance` | `/runepermdropchance <player> [add\|remove\|set] <amount>` · `reset` · `view` | `sweats.runes.admin` |

Durations accept `30s`, `10m`, `12h`, `7d`, `2w`, `1mo` or `permanent`. One month is exactly 30 days.

Run `/login`, `/register` and `/changepass` **without arguments**. The password is then read from the next chat message, which Sweats cancels before it is shown or written to the log. Passwords passed as command arguments are written to the server log by Paper before any plugin can see them, so those forms only exist for compatibility and print a warning.

## Permissions

| Node | Default | Purpose |
|------|---------|---------|
| `sweats.show` | everyone | Use `/show` |
| `sweats.show.bypass` | op | Skip the `/show` cooldown |
| `sweats.admin` | op | Reload the plugin config |
| `sweats.scoreboard.toggle` | everyone | Toggle your own sidebar |
| `sweats.scoreboard.admin` | op | Reload and customize the scoreboard |
| `sweats.scare` | op | Send jumpscares |
| `sweats.scare.bypass` | op | Skip the `/scare` cooldown |
| `sweats.gif` | op | Turn images into mapart |
| `sweats.stats` | op | Open `/stats` for any player |
| `sweats.ram` | op | View heap usage |
| `sweats.ping` | everyone | Use `/ping` |
| `sweats.ping.others` | op | Check another player's ping |
| `sweats.rank.manage` | op | Manage ranks |
| `sweats.nick` | op | Set your own or another player's nickname |
| `sweats.leaderboard` | op | Place and manage themed leaderboard displays |
| `sweats.permission.manage` | op | Grant and revoke nodes |
| `sweats.banperm` | op | Ban players |
| `sweats.kickperm` | op | Kick players |
| `sweats.muteperm` | op | Mute players |
| `sweats.unbanperm` | op | Remove bans |
| `sweats.unmuteperm` | op | Remove mutes |
| `sweats.historyperm` | op | View punishment history |
| `sweats.auth.admin` | op | Auth status/import/unregister, and `/changepass <player> <password>` |
| `sweats.runes` | false | Open the rune browser with `/runes` |
| `sweats.runes.admin` | op | Give runes/table/sword, manage rune luck, access and permanent drop bonuses |
| `sweats.runetable` | false | Open the Runic Table with `/runetable` once the player has table access |

### Server owner

`io.github.near.sweats.owner.Owner` hardcodes the server owner identity:

- UUID: `c4f1f6a2-6669-3cab-97b3-de3e0645584b`
- Name: `Bhosra`

The owner automatically receives every `sweats.*` permission, skips the login gate, and is exempt from this plugin's bans, kicks, mutes, maintenance kicks and team kicks. This protection only applies to Sweats: the console, the server host, operators and other plugins are outside its control. Change the constants in `Owner.java` and rebuild to transfer ownership.

## Built-in ranks

| Rank | Color | Weight |
|------|-------|--------|
| Default | gray | 0 |
| Grinder | `#39FF14` | 10 |
| Sweat | light purple | 20 |
| Sweat+ | dark purple | 30 |
| Admin | red | 40 |
| Community Manager | red | 50 |
| DEV | `#FF8C00` | 60 |

Each player has one rank. Weight sets their position in the tab list and the staff hierarchy: moderators cannot act on a player whose rank weight is higher than their own. Custom ranks are created with `/rank create` and stored in `ranks.yml`.

## Configuration

`config.yml` is re-read on `/sweats reload`. Upgrading the plugin automatically adds missing keys and keeps the old file as `config.yml.bak`.

| Key | Default | Meaning |
|-----|---------|---------|
| `radius` | `100000.0` | How far `/show` messages travel, in blocks. `0` = only the sender. |
| `cooldown-seconds` | `1.5` | Cooldown between `/show` uses. |
| `format` | see `config.yml` | MiniMessage format for `/show`. Tags: `<player>`, `<slot>`, `<item>`. |
| `messages.empty` | — | Message when the selected slot is empty. |
| `messages.cooldown` | — | Message shown while on cooldown. |
| `mentions.enabled` | `true` | Highlight online player names in chat. |
| `mentions.color` | `yellow` | Named color or hex (`#ffd700`) for mentions. |
| `scares.cooldown-seconds` | `3.0` | Cooldown before the same player can be scared again. |
| `gifs.enabled` | `true` | Enable `/gif`. |
| `gifs.folder` | `gifs` | Folder inside the plugin data directory to read images from. |
| `ping.good-max-ms` | `100` | Green up to this latency. |
| `ping.moderate-max-ms` | `200` | Yellow up to this latency, red above. |
| `scoreboard.enabled` | `true` | Per-player sidebar. |
| `scoreboard.default-visible` | `true` | Show the sidebar to players by default. |
| `scoreboard.title` | `&6&lDONUT SMP` | Sidebar title (`&` legacy color codes supported). |
| `scoreboard.lines` | see `config.yml` | Up to 15 configurable templates; supports `%rank%`, `%team%`, `%coin%`, `%shards%`, `%playtime%`, `%online%`, `%server_address%`. |
| `scoreboard.server-address` | empty | Optional sidebar-specific address; empty falls back to `server.address`. |
| `scoreboard.refresh-interval` | `20` | Sidebar refresh rate in ticks (minimum 20, maximum 1200). |
| `tablist.enabled` | `true` | Tablist header and footer. |
| `server.name` | `SURVIVAL MULTIPLAYER` | Name shown in the tablist header. |
| `server.address` | `sweat.playsmp.xyz` | Address shown in the tablist footer and sidebar fallback. An installed config still on the old `Sweats.net` placeholder is moved to the new default once. |
| `leaderboard.enabled` | `true` | Show placed leaderboards. |
| `leaderboard.view-distance` | `48.0` | Only players within this many blocks of a board see it. |
| `leaderboard.refresh-seconds` | `2` | How often each viewer's own score line is refreshed. |
| `leaderboard.ranking-refresh-seconds` | `60` | How often the top ten is recalculated. |
| `afk.enabled` | `true` | Track AFK time for the AFK leaderboard. |
| `afk.timeout-seconds` | `300` | Idle time before a player counts as AFK. |
| `afk.check-seconds` | `1` | How often AFK sessions are checked. |
| `auth.enabled` | `true` | Enable the login gate. |
| `auth.location.*` | world spawn | Where waiting players are placed. Set `use-world-spawn: false` and fill in coordinates for a dedicated login room. |
| `auth.min-password-length` | `8` | Shortest accepted password. |
| `auth.max-password-length` | `128` | Longest accepted password. |
| `auth.login-timeout-seconds` | `300` | Kick after this long without logging in. `0` disables the timeout. |
| `auth.reminder-seconds` | `5` | How often the action-bar reminder repeats. |
| `auth.max-failed-attempts` | `5` | Wrong passwords before a temporary lock. A correct password is still accepted during the lock. |
| `auth.lockout-seconds` | `60` | How long the lock lasts. |
| `auth.attempt-cooldown-ms` | `750` | Minimum gap between password attempts, stored per account and address in `auth.db`. |
| `auth.blindness` | `true` | Blindness effect while waiting. |
| `auth.hide-players` | `true` | Hide waiting players from everyone. |
| `auth.block-chat` | `true` | Cancel chat from waiting players. |
| `auth.block-commands` | `true` | Cancel commands other than `/login`, `/l`, `/register` and `/premium`. |
| `auth.session.enabled` | `true` | Remember successful logins. |
| `auth.session.duration-hours` | `168` | Session lifetime (7 days). |
| `auth.session.bind-ip` | `true` | A session only works from the IP that created it. |
| `auth.premium.enabled` | `true` | Allow Mojang-account logins without a password. |
| `auth.premium.mode` | `PROXY` | `OFF`, `PROXY` (trust UUID v4) or `MOJANG` (only after a Mojang check). |
| `auth.premium.verify-mojang` | `true` | Confirm name/UUID pairs with Mojang. |
| `auth.premium.suggestion` | `true` | Show the `/premium` tip after password logins. Verified premium players never see it; the opt-out is stored per account. |
| `auth.import-simplelogin` | `true` | Copy legacy accounts on startup (optional `plugins/Sweats/legacy-passwords.db`, then the SimpleLogin file). Each import runs once after a successful pass. |
| `auth.simplelogin-database` | `../SimpleLogin/passwords.db` | Old database, relative to `plugins/Sweats` or absolute. |
| `auth.messages.*` | see `config.yml` | MiniMessage text for every prompt and error. |
| `runes.hashes.<id>` | unset | Texture-hash override for one rune (64 hex chars). Unset runes use a fallback item. |
| `runes.drops.chance.<boss>` | `0.104`–`0.119` | COMMON rune chance in percent per Warden/Wither/Dragon/Elder Guardian kill. |
| `runes.drops.rarity-multiplier` | `0.9` | Each rarity step is this much rarer than the one below it. |
| `runes.drops.tier-weights` | `80 / 15 / 5` | Percent chance of the dropped rune being Tier I, II or III. |
| `runes.drops.max-perm-bonus` | `5.0` | Cap for permanent `/runepermdropchance` bonuses, in percentage points. |
| `runes.table.recipe.shape` | `ODO / ETE / OOO` | Three-row crafting shape for the Rune Table. |
| `runes.table.recipe.ingredients` | see `config.yml` | Letter → Material mapping for the Rune Table recipe. |
| `runes.resource-pack.enabled` | `true` | Send the rune resource pack on join. |
| `runes.resource-pack.url` / `sha1` | empty / pack hash | Direct download URL and SHA-1 of `SweatyRunes-pack.zip`. |
| `runes.resource-pack.required` | `false` | Whether the client must accept the rune pack to join. |

Image limits for `/gif`: 16 MB per file, max 8192 px per side, max 4,194,304 total pixels.

## Data files

Everything lives in `plugins/Sweats/`:

| File | Contents |
|------|----------|
| `config.yml` | Settings (auto-upgraded between versions) |
| `ranks.yml` | Custom ranks and player assignments |
| `nicknames.yml` | Player nicknames |
| `leaderboards.yml` | Placed leaderboard positions and categories |
| `real-players.yml` | Confirmed real players (NPCs are never ranked) |
| `afk.yml` | Lifetime AFK time per player |
| `permissions.yml` | Per-player and per-rank permission grants |
| `punishments.yml` | Bans, mutes and their history |
| `stats.yml` | Counters vanilla does not track (blocks placed) |
| `auth.db` | Account hashes, sessions and pending return locations (SQLite) |
| `legacy-passwords.db` | Optional manually supplied legacy account database (not shipped in the jar; imported once) |
| `gifs/` | Images available to `/gif` |
| `runes/access.yml` | Rune Table access grants and permanent boss-drop bonuses |

## Project layout

```
src/main/java/io/github/near/sweats/
├── SweatsPlugin.java     entry point and command wiring
├── auth/                 login gate, password hashing, SQLite store, premium checks
├── chat/                 mention highlighting and rank chat prefixes
├── commands/             one class per command
├── config/               config migration
├── display/              sidebar and tablist rendering
├── leaderboard/          themed leaderboard displays and storage
├── afk/                  AFK tracking and persistence
├── players/              real-player registry (NPC filtering)
├── mapart/               image → mapart conversion and library
├── moderation/           punishments, durations and history
├── nick/                 nicknames and storage
├── permission/           permission nodes and storage
├── ping/  ram/           latency and heap reporting
├── rank/                 rank model, storage and service
├── runes/                rune catalog, effects, items, GUIs, Runic Table, drops
├── scare/                jumpscare presets and playback
├── show/                 /show handling
├── stats/                statistics, menu and storage
└── storage/              YAML helpers
```

## Login and account migration

On a normal offline-mode server the client is sent the player's world, coordinates, inventory and nearby entities *before* a login plugin can freeze them. Freezing alone does not fix that: anyone who types another player's name joins that player's body and can look at their base. Sweats replaces the spawn location in Paper's `AsyncPlayerSpawnLocationEvent`, before world data is sent, and stores the real position in `auth.db`. The real position is restored only after `/login` succeeds. Until then the player is also frozen, invulnerable, hidden from other players, unable to chat, use commands, open inventories, break/place blocks or teleport, and gets no sidebar or tablist entry.

The login area itself is still a real place in a real world, and the client receives the chunks around it. With the default `auth.location.world: ""` and `use-world-spawn: true`, that place is the spawn of the world the player was about to join. For a protected world, put the login area in a separate, empty world (or a void room) and point `auth.location` at it. Blindness and hidden players are client-side obfuscation, not a packet boundary; do not rely on them to keep nearby builds secret. `hide-players: false` intentionally gives up part of this isolation and should only be used on a dedicated login server.

Passwords are stored as PBKDF2-HMAC-SHA256 (210,000 iterations, per-password salt). Five wrong attempts lock the account for a minute by default; a correct password is still accepted during the lock so an attacker cannot deny the owner access. Sessions are optional and, by default, bound to the IP address that logged in. Session restores are only allowed for version-4 UUIDs (an online-mode server or a forwarding proxy); offline accounts whose UUID is derived from the player name always have to enter a password.

One limit is imposed by the vanilla protocol rather than by Sweats: the client is also sent the player's inventory, health and XP as part of the join sequence. The gate blocks clicks, containers, drops and crafting, but a client can still display what it was already sent. If item secrecy matters as much as coordinates, put Mojang accounts behind an online-mode proxy (they never enter the gate) and treat the password gate as protecting identity and location, or use a limbo-style login plugin that intercepts those packets.

### Moving from SimpleLogin

1. Stop the server.
2. Remove (or move out of `plugins/`) the SimpleLogin jar. Two login plugins must not run at the same time.
3. If you have a copy of the old server's `passwords.db`, place it at `plugins/Sweats/legacy-passwords.db` before starting. This file is no longer shipped in the jar.
4. Install the Sweats jar and start the server once.
5. Check the console for `Imported N ... account(s)`, or run `/sweats auth status`.
6. Try logging in with an existing password. It is checked against the old BCrypt hash and rehashed to PBKDF2 on the first successful login, so no player has to reset anything.
7. Keep the old `passwords.db` as a backup until you are satisfied that everyone can log in, then remove the copy from `plugins/Sweats`.

Sweats imports `plugins/Sweats/legacy-passwords.db` first, then `plugins/SimpleLogin/passwords.db` for any account the first file does not contain. Each import is marked as done only after a successful transaction, so an account removed with `/sweats auth unregister` is not recreated on the next restart. `/sweats auth import` forces another pass.

The distributable jar does not contain any credential database. `auth.db` contains password hashes and sessions, so treat it like a backup of `passwords.db`: keep it private and do not post it publicly. Players with weak passwords should change them anyway, since the old server logged command passwords in plaintext.

Imports match accounts by UUID, so migrate while the server uses the same UUID mode (offline, online or proxy) it used with SimpleLogin. Imports never overwrite an account that already exists in `auth.db`. To delete one account, use `/sweats auth unregister <player>`; an online player is moved back to the login area immediately.

If a player forgets their password, an operator can set a new one with `/changepass <player> <newPassword>`. The player does not have to be online. On an offline-mode server the account is also found by its deterministic offline UUID, so imported accounts work even before their first login. An online target is logged out immediately and must use the new password.

If `auth.db` cannot be opened, Sweats **fails closed**: it logs a `SEVERE` error, rejects new connections, and does not release players who are already waiting. Fix the database problem and restart the server; `/sweats reload` does not reopen a database that failed to open at startup.

### Premium (Mojang) accounts

`auth.premium` lets players with a real Minecraft account skip the password, but only when the UUID can prove it:

- **Online-mode server**, or **a proxy that forwards Mojang UUIDs** (`PROXY` mode, the default): the UUID is version 4 and cannot be produced by a plain offline-mode client. With the default `verify-mojang: true`, the player waits in the login area until Mojang confirms the name/UUID pair, and is then returned to their real position. If Mojang is unreachable the proxy is still trusted, so a Mojang outage does not lock out the whole server. Set `verify-mojang: false` only if you fully trust the proxy, in which case the UUID alone is accepted immediately. Keep the backend server unreachable from the internet so nobody can connect to it directly and fake a UUID.
- **`MOJANG` mode** performs the same check but fails closed: if Mojang is down or its answer is inconclusive, the player is asked for their password instead of being let in.
- **`/premium check`** asks Mojang immediately and remembers the result for the current session; `/premium status` shows what was decided.
- **`/premium mute`** (or the clickable opt-out on the login tip) stops the "use `/premium`" reminder from appearing after future logins for that account. `/premium unmute` brings it back.
- **Plain offline-mode server**: UUIDs are version 3, derived from the player name. There is no cryptographic proof that the person typing the name owns the account, so premium bypass is deliberately impossible. `/premium` explains this. The only real fix is switching the network to online-mode or a properly configured proxy.

After each password login (including a restored session), a player who is not verified premium sees a chat line: **use `/premium` if you have a Minecraft account**, plus a clickable **`[Click this so it doesn't send this next time]`** opt-out. The choice is stored on the account. Verified premium players never see it, and the tip is suppressed on a plain offline-mode server where `/premium` could never succeed.

Switching `auth.premium.mode` to `OFF` forces everyone, including Mojang accounts, through `/login`.

### Passwords that leaked through the old setup

Paper writes `/login <password>` to the server log before any plugin can intercept it. If the server previously ran SimpleLogin, treat every password typed that way as compromised: change it with `/changepass`, and ask players to change passwords they use anywhere else. Going forward, use `/login` and `/register` **without arguments** so the password is typed in chat and cancelled before it is logged. You can also set `commands.log: false` in `spigot.yml` (this hides all commands from the log) or install ProtocolLib, which lets a plugin intercept command arguments early.

Back up `plugins/Sweats/auth.db` regularly. It contains password hashes and sessions; it does not contain plaintext passwords.

## Compatibility notes

- **Scoreboards:** Sweats owns each player's sidebar. If another plugin also manages scoreboards, disable one of them to stop them overwriting each other.
- **Chat:** mentions hook Paper's `AsyncChatDecorateEvent`, so they run before formatting plugins build the final message. Disable `mentions.enabled` if another chat plugin wants full control.
- **Mapart:** image decoding runs off the main thread; map creation and inventory delivery happen on the server thread.

## License

No license file is included yet. Treat the code as all rights reserved until one is added.
