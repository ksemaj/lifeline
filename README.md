# Lifeline 1.0.0 (Folia & Paper 26.3)

When one player dies, everyone tied in dies with them. Health is not shared (unless you turn on
damage sharing). Everyone tied in gets an action bar warning when someone is low.

## What's new in 1.7
- **Roster:** whoever is online (and hasn't opted out) when an admin runs `/lifeline start` is
  tied in for that run. Anyone joining later watches as a spectator, or can't join at all if
  "Late joiners" is set to blocked. They're in for the next run. Admins can add or remove people
  mid-run with `/lifeline add|remove <player>`.
- **Opting out only between runs.** Once a run starts, you're in until a wipe, a win or a reset.
- **After a new world**, the next run waits for `/lifeline start` (new roster then).
- **A manual reset** (`/lifeline reset confirm`) ends and records the attempt.
- **Win goal:** Ender Dragon (default), Wither, either, or none.
- **Server rules vs. my settings:** the settings menu is split into Run, Rules (everyone) and
  Display. Every rule change is announced to all players in chat with a plain sentence of what
  it means; display changes and personal settings are confirmed to whoever clicked.
  `/lifeline rules` lists every rule, and players see "Logging out: safe / counts as dying"
  when they join.
- **My settings** (every player): hide low-health warnings, compass arrows, the heartbeat sound
  or the tab header, for yourself only.
- **AFK:** hostile mobs ignore AFK players and drop them as a target. "afk" shows next to the
  name in the tab list and above heads, with hearts beside it.
- **Cleaner tab list:** name, afk, hearts, and the dimension only outside the overworld. No coords.
- **`/coords`** posts your coordinates in chat for everyone (10 s cooldown, can be turned off).
- **One `/tpa` per run** (off by default): the other player clicks [Accept] or [Deny]. The use is
  only spent when accepted; requests expire after 60 s; no teleporting while in combat.

Upgrading from 1.6: your config is backed up as `config-before-1.7.yml` and the messages are
replaced with the new ones (settings are kept). A run that was going when you upgrade is paused;
`/lifeline start` resumes it with everyone online.

## End of a run
- After every wipe or win, a **results bar** appears across the top of everyone's screen for
  90 s, plus a short chat card with a clickable "Full stats" link. Both work on the hardcore
  game-over screen and in spectator mode. `/stats` prints the full breakdown any time.
- **Hardcore:** "New world after wipe" defaults to on, since everyone is a spectator after a
  wipe otherwise.
- If someone dies before `/lifeline start`, they and any admins are told the run hasn't started.

## Install
1. Drop `dist/Lifeline-1.0.0.jar` into the server's `plugins/` folder.
2. Start the server. `plugins/Lifeline/config.yml` is created; edit it and run `/lifeline reload`.
3. Nothing happens until an admin runs **`/lifeline start`** (3-2-1 countdown, or `/lifeline start now`).

The same jar runs on Folia and on Paper. Settings changed in-game are saved in `data.yml`, stats
in `stats.yml`.

## Build it yourself (recommended)
Needs internet access to repo.papermc.io. Gradle downloads Java 25 automatically if you lack it.
```
./gradlew build                    # against the Folia API (default)
./gradlew build -Pplatform=paper   # against the Paper API
```
The jar lands in `build/libs/`.

## Commands
| Command | Who | What |
|---|---|---|
| `/lifeline` or `/lifeline status` | everyone | Is a run going, are you in, how many are tied in |
| `/lifeline list` | everyone | Who is in and who is out |
| `/lifeline rules` | everyone | Every server rule in plain words |
| `/lifeline settings` | everyone | Admins: server menu. Everyone else: My settings |
| `/lifeline stats` or `/stats` | everyone | Last run's breakdown, best time, recent attempts |
| `/lifeline timer` or `/timer` | everyone | How long the current attempt has been going |
| `/coords` | everyone | Post your coordinates in chat |
| `/tpa <player>`, `/tpaccept`, `/tpdeny` | everyone | One teleport per run, if the server allows it |
| `/lifeline optout` / `optin` | everyone | Between runs only |
| `/lifeline start [now]` / `stop` | admin | Start or resume / pause the run |
| `/lifeline add <player>` / `remove <player>` | admin | Change the roster of the run that's going |
| `/lifeline optout <player>` / `optin <player>` | admin | Do it for someone else (works offline too) |
| `/lifeline compass [on\|off]` | admin | Compass tracking on or off |
| `/lifeline sharing [on\|off\|<percent>]` | admin | Damage sharing |
| `/lifeline reset confirm` | admin | End the attempt, restart on a brand-new world |
| `/lifeline resetstats` / `reload` | admin | Wipe stats / reload config |

Aliases: `/fate`, `/ll`. Permissions: `lifeline.use`, `lifeline.toggle` (default on),
`lifeline.admin` (ops), `lifeline.afk.exempt`. Ops can always join, even while late joiners are blocked.
`/tpa` may clash with Essentials; whichever plugin loads last wins.

## Tab list and name tags
- Tab: `Name afk 9❤ nether` (afk and the dimension only when they apply). The header shows the
  attempt, the footer the timer and how many are tied in. Opted-out players show `· out`,
  late joiners `· watching`.
- Above heads: `Name afk 9❤`. **Paper only** (Folia has no scoreboard API). Players already on
  another plugin's scoreboard team are left alone.

## AFK and disconnect protection
- **Combat-log deaths (rule, on by default):** during a run, logging out within 10 s of a fight
  or on critical health (1.5 hearts) counts as dying. No penalty for 30 s after dying,
  respawning, joining or a wipe. Being kicked never counts. When it's off, logging out is
  always safe, and everyone is told so.
- **AFK:** turning your head, chatting, commands and interacting count as active. After 5 min
  idle you're marked afk; hostile mobs then leave you alone (rule "Mobs ignore AFK players").
  After 15 min during a run you're kicked (rule "AFK kick"). The timer pauses while everyone is AFK.

## New world after a wipe (off by default)
Turn on **New world after wipe** in the settings. After a wipe and its summary there is a 3 s
countdown, then everyone is kicked and the server shuts down. On the next start the old world
folders move into `world-backups/` (last 2 kept) and a new world is made from a random seed.
The new world waits for `/lifeline start`.

**The server has to start itself again.** Use the restart loop in `server-scripts/`
(`start.sh` for macOS/Linux, `start.bat` for Windows). Most server hosts restart automatically.

## Choices made (all configurable)
- Only Survival and Adventure players are part of the link.
- Fate kills with `setHealth(0)`, so armour and totems don't save you.
- Deaths caused by fate never start another cascade; deaths within 5 s of a cascade count once.
- Warnings start at 3 hearts and turn red and pulse at 1.5 hearts.
- **Auto-continue after wipe (same world):** on by default; the next attempt starts straight
  away with the same players. Turn it off to wait for `/lifeline start` and a new roster.

## Folia notes
- Every player is only touched from their own entity scheduler; shared state is concurrent or an
  immutable `Settings` snapshot; `data.yml` writes run on the async scheduler.
- Compiles against `dev.folia:folia-api:26.2.build.4-beta`, the newest Folia API published
  when this was written. Bump it in `build.gradle.kts` when a 26.3 build appears.
- The same jar also runs on Paper, since Paper ships the same scheduler API.

## License
GPL-3.0. See [LICENSE](LICENSE).
