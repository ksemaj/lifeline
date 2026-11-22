package dev.nora.lifeline;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * End-of-run results that work in hardcore: on the game-over screen everything is greyed out and
 * afterwards players are spectators, so results go in a bar across the top of the screen (visible
 * in every game mode, including spectator) and in chat. No items, books or menus.
 */
final class StatsScreen {

    private final LifelinePlugin plugin;
    /** The results bar currently on screen, if any. */
    private volatile BossBar bar;
    private volatile long barUntil;

    StatsScreen(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    // ── Results bar ──────────────────────────────────────

    /** Show the results bar to everyone online (and anyone joining) until it expires or a run starts. */
    void showBar(RunStats.Wipe w) {
        hideBar();
        Component text = w.victory()
                ? plugin.msg("bar-victory",
                    Placeholder.unparsed("attempt", String.valueOf(w.attempt())),
                    Placeholder.unparsed("time", RunStats.formatDuration(w.durationMs())))
                : plugin.msg("bar-wipe",
                    Placeholder.unparsed("attempt", String.valueOf(w.attempt())),
                    Placeholder.unparsed("time", RunStats.formatDuration(w.durationMs())),
                    Placeholder.unparsed("victim", w.victim()));
        BossBar b = BossBar.bossBar(text, 1.0f, w.victory() ? BossBar.Color.YELLOW : BossBar.Color.RED,
                BossBar.Overlay.PROGRESS);
        bar = b;
        long seconds = plugin.settings().barSeconds();
        barUntil = System.currentTimeMillis() + seconds * 1000L;
        plugin.forEachPlayer(p -> p.showBossBar(b));
        // Drain the bar over its lifetime, then remove it. Global scheduler = just a clock.
        for (int i = 1; i <= seconds; i++) {
            float progress = 1.0f - (float) i / seconds;
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                if (bar == b) b.progress(Math.max(0f, progress));
            }, i * 20L);
        }
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (bar == b) hideBar();
        }, seconds * 20L + 1);
    }

    /** Called on join: late arrivals still see the result while it's up. */
    void showBarTo(Player player) {
        BossBar b = bar;
        if (b != null && System.currentTimeMillis() < barUntil) player.showBossBar(b);
    }

    void hideBar() {
        BossBar b = bar;
        bar = null;
        if (b != null) plugin.forEachPlayer(p -> p.hideBossBar(b));
    }

    // ── Chat ─────────────────────────────────────────────

    /** Short card for everyone right after the run, with a clickable link to the details. */
    List<Component> card(RunStats.Wipe w) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(plugin.msg(w.victory() ? "end-victory" : "end-wipe",
                Placeholder.unparsed("attempt", String.valueOf(w.attempt()))));
        lines.add(plugin.msg(w.record() && w.attempt() > 1 ? "end-time-record" : "end-time",
                Placeholder.unparsed("time", RunStats.formatDuration(w.durationMs()))));
        if (!w.victory()) {
            lines.add(plugin.msg("end-cause",
                    Placeholder.unparsed("victim", w.victim()),
                    Placeholder.unparsed("cause", shortCause(w))));
        }
        lines.add(plugin.msg("end-view").clickEvent(ClickEvent.runCommand("/lifeline stats")));
        lines.add(Component.empty());
        return lines;
    }

    /** Full breakdown for /stats: the card's facts plus every player's numbers. */
    List<Component> details(RunStats.Wipe w) {
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.msg(w.victory() ? "end-victory" : "end-wipe",
                Placeholder.unparsed("attempt", String.valueOf(w.attempt()))));
        lines.add(plugin.msg(w.record() && w.attempt() > 1 ? "end-time-record" : "end-time",
                Placeholder.unparsed("time", RunStats.formatDuration(w.durationMs()))));
        if (w.victory()) {
            lines.add(plugin.msg("end-dragon", Placeholder.unparsed("victim", w.victim()), Placeholder.unparsed("cause", w.cause())));
        } else {
            lines.add(plugin.msg("end-cause",
                    Placeholder.unparsed("victim", w.victim()),
                    Placeholder.unparsed("cause", shortCause(w))));
            if (!w.taken().isEmpty()) {
                lines.add(plugin.msg("end-taken", Placeholder.unparsed("names", String.join(", ", w.taken()))));
            }
        }
        List<RunStats.Line> squad = new ArrayList<>(w.lines());
        squad.sort(Comparator.comparingDouble(RunStats.Line::damage).reversed());
        for (RunStats.Line l : squad) {
            lines.add(plugin.msg("end-player",
                    Placeholder.unparsed("player", l.name()),
                    Placeholder.unparsed("hearts", LifelinePlugin.hearts(l.damage())),
                    Placeholder.unparsed("kills", String.valueOf(l.kills()))));
        }
        lines.add(plugin.msg("end-total",
                Placeholder.unparsed("hearts", LifelinePlugin.hearts(w.totalDamage())),
                Placeholder.unparsed("kills", String.valueOf(w.totalKills()))));
        return lines;
    }

    /** "Steve was slain by Zombie" → "was slain by Zombie". */
    static String shortCause(RunStats.Wipe w) {
        String c = w.cause() == null ? "" : w.cause();
        if (c.regionMatches(true, 0, w.victim() + " ", 0, w.victim().length() + 1)) {
            c = c.substring(w.victim().length() + 1);
        }
        return c.isBlank() ? "died" : c;
    }

    static boolean anyoneOnline() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }
}
