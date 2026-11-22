# Lifeline 1.0.0

The first public release. If one player dies, everyone dies.

**Runs on:** Paper and Folia, 26.3 and later. One jar for both. Java 25.

### Runs
- An admin starts a run with `/lifeline start`. After a 3-2-1 countdown, everyone online is tied in.
- When anyone tied in dies, everyone else dies too. Armour and totems don't help.
- Players who join late watch as spectators, or admins can block them. Admins can add or remove players mid-run.
- A run ends with a wipe, a win (Ender Dragon, Wither, either, or none) or a manual reset.
- Optionally, after a wipe, the server restarts on a brand-new world. The last 2 worlds are backed up.

### Staying alive together
- Action bar warnings when a teammate is low, with direction and distance.
- Compasses that point at the nearest player in danger, and a heartbeat sound.
- Optional damage sharing (10 to 100% of every hit).

### Fair play
- Server rules are listed with `/lifeline rules`, and every change to them is announced in chat.
- Optional combat-log deaths: logging out mid-fight or on critical health counts as dying. Being kicked never counts.
- AFK detection: mobs ignore AFK players, and there's an optional AFK kick during runs.
- Players can only opt in or out between runs.

### Extras
- `/timer` and `/stats`: run time, the full breakdown, best time and recent attempts.
- A results bar and a chat summary after every run.
- A clean tab list with hearts, `afk` and the dimension. Name tags show them too, on Paper only.
- `/coords` to share your position, and one `/tpa` per player per run (off by default).
- A chest menu for server settings, plus "My settings" for each player.
- Everything is configurable in `config.yml`, with messages in MiniMessage.

### Good to know
- "New world after wipe" needs a server that restarts itself. Most hosts do this, and restart scripts are included.
- On Folia, hearts and `afk` show in the tab list only, because Folia has no scoreboard API.
- `/tpa` may clash with Essentials.
