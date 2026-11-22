package dev.nora.lifeline;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Lifeline for Folia and Paper (one jar: Paper ships the same region schedulers). Threading rules followed throughout:
 * <ul>
 *   <li>A player is only read or changed from that player's own entity scheduler (or an event
 *       already running on their region thread).</li>
 *   <li>Shared state lives in concurrent collections or immutable snapshots ({@link Settings}).</li>
 *   <li>Disk I/O runs on the async scheduler, never on a region tick thread.</li>
 * </ul>
 */
public final class LifelinePlugin extends JavaPlugin {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private volatile Settings settings;
    private FateStore store;
    private RunStats stats;
    private Display display;
    private Activity activity;
    private WorldReset worldReset;
    private StatsScreen statsScreen;
    private volatile boolean countingDown;
    private HealthWatcher healthWatcher;
    private Tpa tpa;

    @Override
    public void onLoad() {
        migrateFromFateLink();
        // A world reset was requested before the last shutdown: clear the old world folders now,
        // before the server loads any world.
        WorldReset.applyPending(getDataFolder(), getServer().getWorldContainer(),
                (int) Math.max(0, getConfig().getLong("world-reset.keep-backups", 2)), getLogger());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeNewDefaults();
        settings = Settings.from(getConfig(), getLogger());
        store = new FateStore(this);
        store.load();
        stats = new RunStats(this);
        stats.load();
        // Save the current attempt's counters every 5 minutes, off the game threads.
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> stats.saveNow(), 5, 5, TimeUnit.MINUTES);

        activity = new Activity(this);
        worldReset = new WorldReset(this);
        statsScreen = new StatsScreen(this);
        display = new Display(this);
        display.applyNametags();
        // Once a second: the play timer only counts while the link runs and someone is online.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> stats.tick(store.isEnabled() && activity.anyoneActive()), 20L, 20L);

        healthWatcher = new HealthWatcher(this);
        getServer().getPluginManager().registerEvents(new FateListener(this), this);
        getServer().getPluginManager().registerEvents(new FateMenu.Clicks(), this);

        FateCommand command = new FateCommand(this);
        for (String name : new String[]{"lifeline", "timer", "stats", "coords"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(command);
                cmd.setTabCompleter(command);
            }
        }

        tpa = new Tpa(this);
        for (String name : new String[]{"tpa", "tpaccept", "tpdeny"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(tpa);
                cmd.setTabCompleter(tpa);
            }
        }
        getServer().getPluginManager().registerEvents(tpa, this);

        // Covers late loads: start a watcher on each online player's own scheduler.
        for (Player player : Bukkit.getOnlinePlayers()) {
            healthWatcher.start(player);
        }
        getLogger().info("Lifeline running on " + (isFolia() ? "Folia" : "Paper") + ". Attempt #"
                + stats.attempt() + ", " + store.optedOut().size() + " player(s) opted out.");
    }

    @Override
    public void onDisable() {
        // Schedulers are shutting down, so write synchronously one last time.
        if (display != null) display.clearNametags();
        if (store != null) store.saveNow();
        if (stats != null) {
            stats.tick(false);
            stats.saveNow();
        }
    }

    void reload() {
        reloadConfig();
        mergeNewDefaults();
        settings = Settings.from(getConfig(), getLogger());
        store.load();
    }

    /**
     * An older config.yml lacks keys added in later versions (new messages, new switches). Copy any
     * missing ones in from the jar's config.yml and save, keeping everything the admin changed.
     */
    private void mergeNewDefaults() {
        getConfig().options().copyDefaults(true);
        // Version 3 (Lifeline) restyled every message, version 4 (1.7) reworked the tab list and
        // added rules, /coords and /tpa. Back up the old config once, then replace the messages
        // with the new ones (settings are kept).
        // get(path, def) ignores the jar defaults, so a config without the key really reads as 1.
        Object rawVersion = getConfig().get("config-version", null);
        long version = rawVersion instanceof Number n ? n.longValue() : 1;
        if (version < 4) {
            try {
                File current = new File(getDataFolder(), "config.yml");
                if (current.exists()) {
                    String backup = version < 3 ? "config-before-lifeline.yml" : "config-before-1.7.yml";
                    Files.copy(current.toPath(), new File(getDataFolder(), backup).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLogger().warning("Could not back up config.yml: " + e);
            }
            ConfigurationSection defaults = getConfig().getDefaults() == null ? null
                    : getConfig().getDefaults().getConfigurationSection("messages");
            if (defaults != null) {
                for (String key : defaults.getKeys(false)) {
                    getConfig().set("messages." + key, defaults.getString(key));
                }
            }
        }
        // Version 2 (1.5.1): shorter reset countdown and a more forgiving combat tag. Apply them
        // once to configs written by older versions, then leave the admin's values alone.
        if (version < 2) {
            getConfig().set("world-reset.countdown-seconds", 3);
            getConfig().set("protection.combat-seconds", 10);
            getConfig().set("config-version", 2);
        }
        if (version < 4) {
            // 1.6 had victory.ender-dragon; 1.7 has victory.goal.
            if (!getConfig().getBoolean("victory.ender-dragon", true)) getConfig().set("victory.goal", "NONE");
            getConfig().set("victory.ender-dragon", null);
            getConfig().set("tab-list.coords", null);
            getConfig().set("health-below-name", null);
            getConfig().set("opt-out.block-while-low", null);
            getConfig().set("config-version", 4);
        }
        saveConfig();
    }

    /**
     * Before the rename the data lived in plugins/FateLink. Copy it over once (the old folder is
     * left alone, so going back to the old jar still works).
     */
    private void migrateFromFateLink() {
        File newDir = getDataFolder();
        File oldDir = new File(newDir.getParentFile(), "FateLink");
        if (newDir.exists() || !oldDir.isDirectory()) return;
        newDir.mkdirs();
        for (String name : new String[]{"config.yml", "data.yml", "stats.yml", "reset-pending.yml"}) {
            File from = new File(oldDir, name);
            if (!from.exists()) continue;
            try {
                Files.copy(from.toPath(), new File(newDir, name).toPath());
            } catch (IOException e) {
                getLogger().warning("Could not copy " + name + " from plugins/FateLink: " + e);
            }
        }
        getLogger().info("Copied config, settings and stats over from plugins/FateLink.");
    }

    Settings settings() {
        return settings;
    }

    StatsScreen statsScreen() {
        return statsScreen;
    }

    WorldReset worldReset() {
        return worldReset;
    }

    Activity activity() {
        return activity;
    }

    Display display() {
        return display;
    }

    boolean isCountingDown() {
        return countingDown;
    }

    /**
     * Starts (or resumes) the link. With a countdown, everyone sees 3, 2, 1 first. Titles go out
     * on each player's own scheduler, the countdown clock on the global scheduler (Folia-safe).
     */
    void startRun(boolean countdown) {
        if (store.isEnabled() || countingDown) return;
        if (!countdown) {
            goLive();
            return;
        }
        countingDown = true;
        for (int i = 0; i < 3; i++) {
            int number = 3 - i;
            getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
                Title title = Title.title(msg("countdown", Placeholder.unparsed("number", String.valueOf(number))),
                        msg("countdown-subtitle"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
                Sound tick = sound("countdown", 1.0f, 1.0f);
                forEachPlayer(p -> {
                    p.showTitle(title);
                    if (tick != null) p.playSound(tick);
                });
            }, 1L + i * 20L);
        }
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            if (countingDown) goLive(); // cancelled if /lifeline stop ran during the countdown
        }, 61L);
    }

    private void goLive() {
        countingDown = false;
        statsScreen.hideBar();
        // Lock in who's playing: everyone online who hasn't opted out. Resuming a paused run keeps
        // its roster. Late joiners we made spectators last time get to play this time.
        if (store.roster().isEmpty()) {
            List<UUID> ids = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!store.isOptedOut(p.getUniqueId())) ids.add(p.getUniqueId());
            }
            store.setRoster(ids);
            if (tpa != null) tpa.clear();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (store.inRoster(p.getUniqueId()) && store.wasMadeSpectator(p.getUniqueId())) {
                p.getScheduler().run(this, t -> releaseSpectator(p), null);
            }
        }
        store.setEnabled(true);
        stats.tick(!Bukkit.getOnlinePlayers().isEmpty());
        Title title = Title.title(msg("start-title"),
                msg("start-subtitle", Placeholder.unparsed("attempt", String.valueOf(stats.attempt()))),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        Sound go = sound("start", 1.0f, 1.0f);
        forEachPlayer(p -> {
            p.showTitle(title);
            if (go != null) p.playSound(go);
        });
        getServer().sendMessage(prefixed("started", Placeholder.unparsed("attempt", String.valueOf(stats.attempt()))));
    }

    /** The run is over (wipe without auto-continue, win, hardcore wipe, world reset). */
    void endRun() {
        countingDown = false;
        store.setEnabled(false);
        stats.tick(false);
        store.clearRoster();
    }

    /** Put a late joiner we made a spectator back into survival at spawn. Player's own thread. */
    void releaseSpectator(Player p) {
        store.markSpectator(p.getUniqueId(), false);
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
            p.teleportAsync(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    void stopRun() {
        countingDown = false;
        store.setEnabled(false);
        stats.tick(false);
        stats.save();
        getServer().sendMessage(prefixed("stopped",
                Placeholder.unparsed("time", RunStats.formatDuration(stats.elapsedMs()))));
    }

    /** Run something for every online player on that player's own thread. */
    void forEachPlayer(java.util.function.Consumer<Player> action) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, task -> action.accept(p), null);
        }
    }

    RunStats stats() {
        return stats;
    }

    /** Same jar runs on both; this is only used for the startup log line. */
    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    FateStore store() {
        return store;
    }

    HealthWatcher healthWatcher() {
        return healthWatcher;
    }

    /**
     * A player takes part in the link right now: link active, not opted out, in an affected game
     * mode. Call it from the player's own region thread, since it reads their game mode.
     */
    boolean isLinked(Player player) {
        return isOptedIn(player) && settings.affectedModes().contains(player.getGameMode());
    }

    /** Run active and the player is on its roster. Safe from any thread. */
    boolean isOptedIn(Player player) {
        return store.isEnabled() && store.inRoster(player.getUniqueId()) && !store.isOptedOut(player.getUniqueId());
    }

    /** One line for joins: the rule people actually worry about, whether leaving kills them. */
    Component rulesLine() {
        return prefixed("rules-join", Placeholder.component("logout",
                msg(store.get(Toggle.COMBAT_LOG) ? "rules-logout-deadly" : "rules-logout-safe")));
    }

    // ── Messages ─────────────────────────────────────────

    Component msg(String key, TagResolver... resolvers) {
        String raw = settings.messages().getOrDefault(key, "<red>Missing message: " + key);
        return mini.deserialize(raw, resolvers);
    }

    Component prefixed(String key, TagResolver... resolvers) {
        return msg("prefix").append(msg(key, resolvers));
    }

    Sound sound(String key, float volume, float pitch) {
        String raw = settings.sounds().get(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Sound.sound(Key.key(raw), Sound.Source.MASTER, volume, pitch);
        } catch (Exception e) {
            getLogger().warning("Invalid sound '" + raw + "' for sounds." + key);
            return null;
        }
    }

    static String hearts(double health) {
        double hearts = Math.max(0, health) / 2.0;
        return hearts == Math.floor(hearts)
                ? String.valueOf((int) hearts)
                : String.format(Locale.ROOT, "%.1f", hearts);
    }
}
