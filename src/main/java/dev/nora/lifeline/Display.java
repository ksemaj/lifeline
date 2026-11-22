package dev.nora.lifeline;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab list (name, afk, hearts, dimension when not in the overworld; attempt and timer on top) and
 * the same afk and hearts next to names above heads.
 */
final class Display {

    private static final String TEAM_PREFIX = "ll_";

    private final LifelinePlugin plugin;
    /** Last tab name sent per player, so we only send packets when something changed. */
    private final Map<UUID, Component> lastTabName = new ConcurrentHashMap<>();
    private final Map<UUID, Component> lastTag = new ConcurrentHashMap<>();
    private volatile boolean nametagWarned;

    Display(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    // ── Tab list (runs on each player's own thread, from HealthWatcher) ──

    void updateTab(Player player) {
        FateStore store = plugin.store();
        UUID id = player.getUniqueId();
        if (!store.get(Toggle.TAB_INFO)) {
            if (lastTabName.remove(id) != null) {
                player.playerListName(null);
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            }
            return;
        }

        String key;
        if (!store.isEnabled() || plugin.isOptedIn(player)) key = "tab-name";
        else if (player.getGameMode() == GameMode.SPECTATOR && store.wasMadeSpectator(id)) key = "tab-name-watching";
        else key = "tab-name-out";
        Component name = plugin.msg(key,
                Placeholder.component("name", player.name()),
                Placeholder.component("afk", afkTag(id)),
                Placeholder.component("health", health(player)),
                Placeholder.component("dimension", dimension(player.getWorld())));
        if (!name.equals(lastTabName.put(id, name))) {
            player.playerListName(name);
        }

        if (!store.pref(id, Pref.TAB_INFO)) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            return;
        }
        RunStats stats = plugin.stats();
        long linked = Bukkit.getOnlinePlayers().stream().filter(plugin::isOptedIn).count();
        Component header = plugin.msg("tab-header", Placeholder.unparsed("attempt", String.valueOf(stats.attempt())));
        Component footer = store.isEnabled()
                ? plugin.msg("tab-footer",
                    Placeholder.unparsed("time", RunStats.formatClock(stats.elapsedMs())),
                    Placeholder.unparsed("linked", String.valueOf(linked)))
                : plugin.msg("tab-footer-waiting");
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    void forget(UUID id) {
        lastTabName.remove(id);
        lastTag.remove(id);
        if (LifelinePlugin.isFolia()) return;
        try {
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(id));
            if (team != null) team.unregister();
        } catch (RuntimeException ignored) {
            // Server shutting down.
        }
    }

    private Component afkTag(UUID id) {
        return plugin.store().get(Toggle.AFK_FLAG) && plugin.activity().isAfk(id) ? plugin.msg("tag-afk") : Component.empty();
    }

    private static Component health(Player player) {
        double hp = Math.max(0, player.getHealth());
        double max = 20;
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) max = attr.getValue();
        double ratio = max > 0 ? hp / max : 0;
        TextColor color = ratio > 0.6 ? NamedTextColor.GREEN : ratio > 0.3 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text(LifelinePlugin.hearts(hp) + "❤", color);
    }

    /** Empty in the overworld, so the tab list stays quiet most of the time. */
    private Component dimension(World world) {
        if (world == null) return Component.empty();
        return switch (world.getEnvironment()) {
            case NETHER -> plugin.msg("dimension-nether");
            case THE_END -> plugin.msg("dimension-end");
            default -> Component.empty();
        };
    }

    // ── Above heads: "name afk 9❤" (Paper only) ──────────

    /**
     * Each player gets their own scoreboard team whose suffix is the afk tag and hearts. Folia has
     * no scoreboard API, so there this does nothing ({@link #applyNametags} logs a note once).
     */
    void updateNametag(Player player) {
        if (LifelinePlugin.isFolia()) return;
        UUID id = player.getUniqueId();
        boolean on = plugin.store().get(Toggle.NAMETAG) && player.getGameMode() != GameMode.SPECTATOR;
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = board.getTeam(teamName(id));
            if (!on) {
                if (team != null) team.unregister();
                lastTag.remove(id);
                return;
            }
            Component suffix = plugin.msg("nametag",
                    Placeholder.component("afk", afkTag(id)),
                    Placeholder.component("health", health(player)));
            if (team == null) {
                // Don't take players away from another plugin's team (ranks, colours).
                if (board.getEntryTeam(player.getName()) != null) return;
                team = board.registerNewTeam(teamName(id));
                team.addEntry(player.getName());
                lastTag.remove(id);
            }
            if (!suffix.equals(lastTag.put(id, suffix))) team.suffix(suffix);
        } catch (RuntimeException e) {
            if (!nametagWarned) {
                nametagWarned = true;
                plugin.getLogger().warning("Could not update name tags: " + e);
            }
        }
    }

    /** Called at startup and when the name tag switch changes. */
    void applyNametags() {
        boolean on = plugin.store().get(Toggle.NAMETAG);
        if (LifelinePlugin.isFolia()) {
            if (on && !nametagWarned) {
                nametagWarned = true;
                plugin.getLogger().info("Hearts and afk above heads need Paper: Folia has no scoreboard API. "
                        + "They still show in the tab list.");
            }
            return;
        }
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            // The old "health under name" objectives from 1.6 and FateLink.
            for (String old : new String[]{"lifeline_hp", "fatelink_hp"}) {
                Objective objective = board.getObjective(old);
                if (objective != null) objective.unregister();
            }
            if (!on) clearNametags();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not set up name tags: " + e);
        }
    }

    /** Remove our teams (switch turned off, or shutdown so nothing lingers in the scoreboard file). */
    void clearNametags() {
        lastTag.clear();
        if (LifelinePlugin.isFolia()) return;
        try {
            for (Team team : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
                if (team.getName().startsWith(TEAM_PREFIX)) team.unregister();
            }
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    private static String teamName(UUID id) {
        return TEAM_PREFIX + id.toString().replace("-", "").substring(0, 13);
    }
}
