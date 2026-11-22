package dev.nora.lifeline;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class FateListener implements Listener {

    private final LifelinePlugin plugin;

    /**
     * Players currently being killed by a cascade, mapped to who started it. Their own death
     * must never start a new cascade; that is the guard against recursive death loops.
     */
    private final Map<UUID, String> cascadeVictims = new ConcurrentHashMap<>();

    /**
     * Players currently taking a shared slice of someone else's hit, mapped to whose hit it was.
     * Damage they take while in here is never shared again, so sharing can't bounce around.
     */
    private final Map<UUID, String> sharingVictims = new ConcurrentHashMap<>();

    /** Players being killed because they logged out in combat / on low health. */
    private final java.util.Set<UUID> combatLoggers = ConcurrentHashMap.newKeySet();

    /** Until this time (epoch millis) further deaths don't start a new cascade. */
    private final AtomicLong cascadeUntil = new AtomicLong();

    FateListener(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    // ── Deaths and the cascade ───────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        plugin.healthWatcher().clearLow(dead.getUniqueId());
        plugin.activity().grace(dead.getUniqueId());

        // Killed by fate: rewrite the death message and stop here (no recursion).
        String origin = cascadeVictims.get(dead.getUniqueId());
        if (origin != null) {
            event.deathMessage(plugin.msg("death-message",
                    Placeholder.component("player", dead.name()),
                    Placeholder.unparsed("victim", origin)));
            return;
        }

        // Logged out mid-fight or on low health: counts as a real death.
        if (combatLoggers.contains(dead.getUniqueId())) {
            event.deathMessage(plugin.msg("logout-death-message", Placeholder.component("player", dead.name())));
        }

        // Killed by a shared slice of damage: that's a real death and does start the cascade.
        String sharedFrom = sharingVictims.get(dead.getUniqueId());
        if (sharedFrom != null) {
            event.deathMessage(plugin.msg("shared-death-message",
                    Placeholder.component("player", dead.name()),
                    Placeholder.unparsed("victim", sharedFrom)));
        }

        if (!plugin.store().isEnabled() && !plugin.store().isOptedOut(dead.getUniqueId())) {
            // The most common "nothing happened": the run was never started.
            String key = plugin.worldReset().inProgress() ? "death-during-reset"
                    : plugin.stats().attempt() > 1 ? "death-between-runs" : "death-not-started";
            Component note = plugin.prefixed(key, Placeholder.unparsed("player", dead.getName()));
            dead.sendMessage(note);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != dead && p.hasPermission("lifeline.admin")) p.sendMessage(note);
            }
            return;
        }
        if (!plugin.isLinked(dead)) return;

        // Only one cascade at a time: near-simultaneous deaths (one creeper, two players) count once.
        // Atomic because on Folia two players in different regions can die on the same tick.
        long now = System.currentTimeMillis();
        long until = cascadeUntil.get();
        if (now < until || !cascadeUntil.compareAndSet(until, now + plugin.settings().cascadeCooldownMs())) return;

        Component message = event.deathMessage();
        String cause = message == null ? "died" : PlainTextComponentSerializer.plainText().serialize(message);
        startCascade(dead, cause);
    }

    /** Runs on the dead player's region thread. Everyone else is handled on their own thread. */
    private void startCascade(Player origin, String cause) {
        Settings settings = plugin.settings();
        String originName = origin.getName();
        UUID originId = origin.getUniqueId();

        Title title = Title.title(
                plugin.msg("cascade-title"),
                plugin.msg("cascade-subtitle", Placeholder.unparsed("victim", originName)),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        Sound sound = plugin.sound("cascade", 1.0f, 0.7f);

        // Only thread-safe checks here (opt-out list). Game mode and alive state belong to the
        // target's region, so they're checked inside the target's own task below.
        List<Player> targets = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(originId) && plugin.isOptedIn(p)) targets.add(p);
        }

        // The one who fell is on this thread already.
        origin.showTitle(title);
        if (sound != null) origin.playSound(sound);

        Queue<String> claimed = new ConcurrentLinkedQueue<>();
        AtomicInteger pending = new AtomicInteger(targets.size());
        Runnable finishWhenDone = () -> {
            if (pending.decrementAndGet() == 0) finish(originName, cause, List.copyOf(claimed));
        };
        if (targets.isEmpty()) finish(originName, cause, List.of());

        for (Player target : targets) {
            // Title and sound straight away, the kill after the delay, all on the target's thread.
            target.getScheduler().run(plugin, t -> {
                if (!plugin.isLinked(target) || target.isDead()) return;
                target.showTitle(title);
                if (sound != null) target.playSound(sound);
            }, null);

            ScheduledTask kill = target.getScheduler().runDelayed(plugin, task -> {
                try {
                    if (!target.isOnline() || target.isDead() || !plugin.isLinked(target)) return;
                    if (plugin.store().get(Toggle.LIGHTNING)) target.getWorld().strikeLightningEffect(target.getLocation());
                    cascadeVictims.put(target.getUniqueId(), originName);
                    try {
                        // setHealth(0) bypasses armour and totems: fate isn't negotiable.
                        target.setHealth(0.0);
                        claimed.add(target.getName());
                    } finally {
                        cascadeVictims.remove(target.getUniqueId());
                    }
                } finally {
                    finishWhenDone.run();
                }
            }, finishWhenDone, settings.cascadeDelayTicks());
            // null means the player left between the online check and now: nothing to kill.
            if (kill == null) finishWhenDone.run();
        }
    }

    /** Close the attempt, then show everyone the end-of-run screen. */
    private void finish(String originName, String cause, List<String> taken) {
        Settings settings = plugin.settings();
        // The attempt always ends and is recorded; the toggle only controls the stats screen.
        RunStats.Wipe wipe = plugin.stats().endRun(originName, cause, taken, false);
        plugin.activity().graceAll();
        List<Component> extra = new ArrayList<>();
        if (plugin.store().get(Toggle.WORLD_RESET)) {
            // Fresh world: countdown starts once everyone has had a look at the stats. The new
            // world then waits for /lifeline start, and whoever is online then is the new roster.
            plugin.endRun();
            plugin.worldReset().schedule(settings.summaryDelayTicks() + settings.resetDelayTicks());
        } else if (plugin.getServer().isHardcore()) {
            // Everyone is a spectator now and stays one: the run is over (timer and AFK kicks stop).
            plugin.endRun();
            extra.add(plugin.prefixed("hardcore-no-reset"));
        } else if (!plugin.store().get(Toggle.AUTO_CONTINUE)) {
            // Next attempt waits for an admin to run /lifeline start again.
            plugin.endRun();
            extra.add(plugin.prefixed("waiting-for-start"));
        }
        presentEnd(wipe, settings.summaryDelayTicks(), extra);
    }

    /**
     * Chat card and the results bar for everyone, after a short delay. Both work on the hardcore
     * game-over screen and in spectator mode.
     */
    void presentEnd(RunStats.Wipe wipe, long delayTicks, List<Component> extra) {
        if (!plugin.store().get(Toggle.STATS_SUMMARY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(plugin.prefixed(wipe.victory() ? "end-victory" : "cascade-broadcast",
                    Placeholder.unparsed("victim", wipe.victim()),
                    Placeholder.unparsed("attempt", String.valueOf(wipe.attempt())),
                    Placeholder.unparsed("count", String.valueOf(wipe.taken().size()))));
            lines.addAll(extra);
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                    task -> lines.forEach(Bukkit.getServer()::sendMessage), delayTicks);
            return;
        }
        List<Component> lines = new ArrayList<>(plugin.statsScreen().card(wipe));
        lines.addAll(extra);
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            lines.forEach(Bukkit.getServer()::sendMessage);
            plugin.statsScreen().showBar(wipe);
        }, delayTicks);
    }

    // ── Damage: stats and optional sharing ───────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player hurt)) return;
        if (!plugin.isLinked(hurt)) return;
        double dealt = event.getFinalDamage();
        if (dealt <= 0) return;

        plugin.stats().recordDamage(hurt.getUniqueId(), hurt.getName(), dealt);

        // A shared slice is never shared again: this is what stops sharing from looping.
        if (sharingVictims.containsKey(hurt.getUniqueId())) return;
        if (!plugin.store().isSharing()) return;

        double slice = dealt * plugin.store().sharingFraction();
        if (slice < 0.05) return;
        String fromName = hurt.getName();
        UUID fromId = hurt.getUniqueId();

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(fromId) || !plugin.isOptedIn(other)) continue;
            // Apply on the other player's own region thread (Folia-safe).
            other.getScheduler().run(plugin, task -> {
                if (other.isDead() || !plugin.isLinked(other)) return;
                sharingVictims.put(other.getUniqueId(), fromName);
                int iframes = other.getNoDamageTicks();
                try {
                    other.damage(slice);
                } finally {
                    sharingVictims.remove(other.getUniqueId());
                    // A shared slice must not make them immune to the next real hit.
                    if (!other.isDead()) other.setNoDamageTicks(iframes);
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) return;
        Goal goal = plugin.store().goal();
        if (dead instanceof EnderDragon && (goal == Goal.DRAGON || goal == Goal.EITHER)) {
            onVictory(dead.getKiller(), "slew the Ender Dragon", "victory-subtitle");
        } else if (dead instanceof Wither && (goal == Goal.WITHER || goal == Goal.EITHER)) {
            onVictory(dead.getKiller(), "slew the Wither", "victory-subtitle-wither");
        }
        Player killer = dead.getKiller();
        if (killer != null && plugin.isOptedIn(killer)) {
            plugin.stats().recordKill(killer.getUniqueId(), killer.getName());
        }
    }

    /** Killing the goal boss during a run wins it: the run ends with the victory screen. */
    private void onVictory(Player killer, String cause, String subtitleKey) {
        if (!plugin.store().isEnabled()) return;
        String by = killer != null ? killer.getName() : "the squad";
        RunStats.Wipe win = plugin.stats().endRun(by, cause, List.of(), true);
        plugin.endRun();
        plugin.activity().graceAll();

        Title title = Title.title(plugin.msg("victory-title"),
                plugin.msg(subtitleKey, Placeholder.unparsed("time", RunStats.formatDuration(win.durationMs()))),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        Sound fanfare = plugin.sound("victory", 1.0f, 1.0f);
        plugin.forEachPlayer(p -> {
            p.showTitle(title);
            if (fanfare != null) p.playSound(fanfare);
        });
        presentEnd(win, 100L, List.of(plugin.prefixed("waiting-for-start")));
    }

    // ── Join / respawn / quit ────────────────────────────

    /** Late joiners can't get in while a run is going (if the server is set to block them). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        FateStore store = plugin.store();
        if (!store.isEnabled() || store.lateJoin() != LateJoin.BLOCK) return;
        UUID id = event.getUniqueId();
        if (store.inRoster(id) || store.isOptedOut(id)) return;
        if (Bukkit.getOfflinePlayer(id).isOp()) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, plugin.msg("late-join-blocked",
                Placeholder.unparsed("attempt", String.valueOf(plugin.stats().attempt()))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        FateStore store = plugin.store();
        plugin.healthWatcher().start(player);
        plugin.activity().grace(id);
        plugin.statsScreen().showBarTo(player);
        if (!store.isEnabled()) {
            // Watched last run from the sidelines: they're in for the next one.
            if (store.wasMadeSpectator(id)) plugin.releaseSpectator(player);
            player.sendMessage(plugin.prefixed("join-waiting"));
            player.sendMessage(plugin.rulesLine());
            return;
        }

        if (!store.inRoster(id) && !store.isOptedOut(id)) {
            // Joined after the start: watch this one, play the next.
            if (store.lateJoin() == LateJoin.SPECTATE && plugin.settings().affectedModes().contains(player.getGameMode())) {
                store.markSpectator(id, true);
                player.setGameMode(GameMode.SPECTATOR);
            }
            player.sendMessage(plugin.prefixed("join-late"));
            return;
        }

        if (plugin.isOptedIn(player)) {
            long others = Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !p.equals(player) && plugin.isOptedIn(p))
                    .count();
            player.sendMessage(plugin.prefixed("join", Placeholder.unparsed("count", String.valueOf(others))));
            player.sendMessage(plugin.rulesLine());
        } else {
            player.sendMessage(plugin.prefixed("join-opted-out"));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // No-op if the watcher survived the death; restarts it if the server retired it.
        Player player = event.getPlayer();
        plugin.healthWatcher().start(player);
        plugin.activity().grace(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        try {
            if (shouldPunishLogout(player, event.getReason())) {
                boolean low = player.getHealth() <= plugin.settings().criticalAt();
                Bukkit.getServer().sendMessage(plugin.prefixed(low ? "logout-low" : "logout-combat",
                        Placeholder.unparsed("player", player.getName())));
                // Runs on the quitting player's own thread. Their death starts the cascade as usual.
                combatLoggers.add(id);
                try {
                    player.setHealth(0.0);
                } finally {
                    combatLoggers.remove(id);
                }
            }
        } finally {
            plugin.healthWatcher().forget(id);
        }
    }

    /** Logging out mid-fight or on low health during a run counts as dying. Kicks never do. */
    private boolean shouldPunishLogout(Player player, PlayerQuitEvent.QuitReason reason) {
        if (!plugin.store().get(Toggle.COMBAT_LOG) || reason == PlayerQuitEvent.QuitReason.KICKED) return false;
        if (player.isDead() || !plugin.isLinked(player)) return false;
        // Forgiving: no penalty just after dying, respawning, joining or a wipe, and "low" means
        // critical health (1.5 hearts by default), not merely the warning level.
        if (plugin.activity().inGrace(player.getUniqueId())) return false;
        return plugin.activity().inCombat(player.getUniqueId()) || player.getHealth() <= plugin.settings().criticalAt();
    }

    // ── Activity (AFK) and combat tagging ────────────────

    /** Hostile mobs leave AFK players alone (server rule, on by default). */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player target) || !(event.getEntity() instanceof Enemy)) return;
        if (plugin.store().get(Toggle.AFK_PROTECT) && plugin.activity().isAfk(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitBy(EntityDamageByEntityEvent event) {
        // Both sides of a fight are "in combat".
        if (event.getEntity() instanceof Player hurt) plugin.activity().markCombat(hurt.getUniqueId());
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
        if (source instanceof Player attacker) plugin.activity().markCombat(attacker.getUniqueId());
        if (event.getEntity() instanceof Player hurt && source instanceof Mob) plugin.activity().markCombat(hurt.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        plugin.activity().markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        plugin.activity().markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        plugin.activity().markActive(event.getPlayer());
    }
}
