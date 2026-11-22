package dev.nora.lifeline;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK detection and combat tagging. Everything is keyed by UUID in concurrent maps; the per-player
 * check runs from that player's own HealthWatcher tick, so it's Folia-safe.
 *
 * A player counts as active when they turn their head, chat, run a command or interact. Position
 * alone doesn't count, so water streams and minecarts can't keep someone "active".
 */
final class Activity {

    private record Look(float yaw, float pitch) {}

    private final LifelinePlugin plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<UUID, Look> lastLook = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();

    Activity(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    void markActive(Player player) {
        markActive(player.getUniqueId(), player.getName());
    }

    void markActive(UUID id, String name) {
        lastActive.put(id, System.currentTimeMillis());
        if (afk.remove(id) && plugin.store().get(Toggle.AFK_FLAG)) {
            Bukkit.getServer().sendMessage(plugin.prefixed("afk-back", Placeholder.unparsed("player", name)));
        }
    }

    /** Wipe someone's combat tag and give them a grace period (after death, respawn or join). */
    void grace(UUID id) {
        lastCombat.remove(id);
        graceUntil.put(id, System.currentTimeMillis() + plugin.settings().logoutGraceMs());
    }

    /** Grace for everyone at once, e.g. right after a wipe. */
    void graceAll() {
        long until = System.currentTimeMillis() + plugin.settings().logoutGraceMs();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastCombat.remove(p.getUniqueId());
            graceUntil.put(p.getUniqueId(), until);
        }
    }

    boolean inGrace(UUID id) {
        Long t = graceUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    void markCombat(UUID id) {
        lastCombat.put(id, System.currentTimeMillis());
    }

    boolean inCombat(UUID id) {
        Long t = lastCombat.get(id);
        return t != null && System.currentTimeMillis() - t < plugin.settings().combatMs();
    }

    boolean isAfk(UUID id) {
        return afk.contains(id);
    }

    /** True if at least one online player isn't AFK (the timer only counts then). */
    boolean anyoneActive() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!afk.contains(p.getUniqueId())) return true;
        }
        return false;
    }

    void forget(UUID id) {
        lastActive.remove(id);
        lastLook.remove(id);
        lastCombat.remove(id);
        graceUntil.remove(id);
        afk.remove(id);
    }

    /** Runs on the player's own thread every watcher tick. */
    void tick(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Location loc = player.getLocation();
        Look look = new Look(loc.getYaw(), loc.getPitch());
        Look before = lastLook.put(id, look);
        lastActive.putIfAbsent(id, now);
        if (before != null && (Math.abs(before.yaw() - look.yaw()) > 0.5f || Math.abs(before.pitch() - look.pitch()) > 0.5f)) {
            markActive(id, player.getName());
            return;
        }

        FateStore store = plugin.store();
        Settings settings = plugin.settings();
        long idle = now - lastActive.getOrDefault(id, now);

        // Spectators, creative players and opted-out players can idle freely.
        if (!plugin.isLinked(player)) {
            if (afk.remove(id)) lastActive.put(id, now);
            return;
        }
        if (idle >= settings.afkFlagMs() && afk.add(id)) {
            if (store.get(Toggle.AFK_FLAG)) {
                Bukkit.getServer().sendMessage(plugin.prefixed("afk-flagged", Placeholder.unparsed("player", player.getName())));
            }
            if (store.get(Toggle.AFK_PROTECT)) dropTargets(player);
        }
        // Kicks only matter while a run is going: an AFK player can wipe everyone.
        if (store.get(Toggle.AFK_KICK) && store.isEnabled() && idle >= settings.afkKickMs()
                && !player.hasPermission("lifeline.afk.exempt")) {
            player.kick(plugin.msg("afk-kick-reason"));
            Bukkit.getServer().sendMessage(plugin.prefixed("afk-kicked", Placeholder.unparsed("player", player.getName())));
        }
    }

    /** Mobs already chasing a player who just went AFK lose interest (new targeting is blocked in FateListener). */
    private static void dropTargets(Player player) {
        try {
            for (org.bukkit.entity.Entity e : player.getNearbyEntities(32, 16, 32)) {
                if (e instanceof org.bukkit.entity.Mob mob && e instanceof org.bukkit.entity.Enemy
                        && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                }
            }
        } catch (RuntimeException ignored) {
            // On Folia a mob across a region border can't be touched from here; it gives up on its own.
        }
    }
}
