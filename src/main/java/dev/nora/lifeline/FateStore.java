package dev.nora.lifeline;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Persists who opted out and whether the link is active, in plugins/Lifeline/data.yml (plus the run roster, teleports used and personal settings).
 * Thread-safe: on Folia, commands and events run on many threads at once.
 */
final class FateStore {

    private final LifelinePlugin plugin;
    private final File file;
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
    /** "Running": false until an admin runs /fate start. */
    private volatile boolean enabled = false;
    private volatile boolean sharing;
    private volatile double sharingFraction = 0.25;
    private final java.util.Map<Toggle, Boolean> toggles = new java.util.concurrent.ConcurrentHashMap<>();
    /** Who is in the current run: everyone online (and not opted out) at /lifeline start, plus /lifeline add. */
    private final Set<UUID> roster = ConcurrentHashMap.newKeySet();
    /** Late joiners we switched to spectator, so we can put them back in survival later. */
    private final Set<UUID> madeSpectator = ConcurrentHashMap.newKeySet();
    /** Players who already used their one teleport this run. */
    private final Set<UUID> tpaUsed = ConcurrentHashMap.newKeySet();
    /** Per-player preferences that are switched off (everything is on by default). */
    private final java.util.Map<UUID, Set<Pref>> prefsOff = new ConcurrentHashMap<>();
    private volatile Goal goal = Goal.DRAGON;
    private volatile LateJoin lateJoin = LateJoin.SPECTATE;

    FateStore(LifelinePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        optedOut.clear();
        Settings defaults = plugin.settings();
        enabled = false;
        sharing = defaults.sharingDefault();
        sharingFraction = defaults.sharingFractionDefault();
        toggles.clear();
        for (Toggle t : Toggle.values()) {
            toggles.put(t, plugin.getConfig().getBoolean(t.configPath, t.fallback));
        }
        // Hardcore: after a wipe everyone is a spectator for good, so a new world is the only way
        // to play on. Default the reset on there (still switchable in /lifeline settings).
        if (plugin.getServer().isHardcore()) {
            toggles.put(Toggle.WORLD_RESET, true);
        }
        roster.clear();
        madeSpectator.clear();
        tpaUsed.clear();
        prefsOff.clear();
        goal = Goal.parse(plugin.getConfig().getString("victory.goal", "DRAGON"), Goal.DRAGON);
        lateJoin = LateJoin.parse(plugin.getConfig().getString("late-joiners", "SPECTATE"), LateJoin.SPECTATE);
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        enabled = yaml.getBoolean("enabled", false);
        goal = Goal.parse(yaml.getString("victory-goal", goal.name()), goal);
        lateJoin = LateJoin.parse(yaml.getString("late-joiners", lateJoin.name()), lateJoin);
        readUuids(yaml, "roster", roster);
        readUuids(yaml, "made-spectator", madeSpectator);
        readUuids(yaml, "tpa-used", tpaUsed);
        org.bukkit.configuration.ConfigurationSection prefs = yaml.getConfigurationSection("prefs-off");
        if (prefs != null) {
            for (String key : prefs.getKeys(false)) {
                try {
                    Set<Pref> off = ConcurrentHashMap.newKeySet();
                    for (String p : prefs.getStringList(key)) {
                        for (Pref pref : Pref.values()) if (pref.key().equals(p)) off.add(pref);
                    }
                    prefsOff.put(UUID.fromString(key), off);
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        if (enabled && roster.isEmpty()) {
            // Run from before rosters existed: make the admin start it again so everyone is counted.
            enabled = false;
            plugin.getLogger().info("The run was paused: start it again with /lifeline start to lock in who's playing.");
        }
        sharing = yaml.getBoolean("damage-sharing.enabled", sharing);
        sharingFraction = Settings.clampFraction(yaml.getDouble("damage-sharing.fraction", sharingFraction));
        for (Toggle t : Toggle.values()) {
            toggles.put(t, yaml.getBoolean("toggles." + t.dataKey(), toggles.get(t)));
        }
        for (String raw : yaml.getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Skipping invalid UUID in data.yml: " + raw);
            }
        }
    }

    /** Queue a save on Folia's async scheduler so region threads never block on disk. */
    void save() {
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> saveNow());
    }

    synchronized void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", enabled);
        yaml.set("damage-sharing.enabled", sharing);
        yaml.set("damage-sharing.fraction", sharingFraction);
        for (Toggle t : Toggle.values()) yaml.set("toggles." + t.dataKey(), get(t));
        yaml.set("opted-out", optedOut.stream().map(UUID::toString).sorted().collect(Collectors.toList()));
        yaml.set("victory-goal", goal.name());
        yaml.set("late-joiners", lateJoin.name());
        yaml.set("roster", roster.stream().map(UUID::toString).sorted().collect(Collectors.toList()));
        yaml.set("made-spectator", madeSpectator.stream().map(UUID::toString).sorted().collect(Collectors.toList()));
        yaml.set("tpa-used", tpaUsed.stream().map(UUID::toString).sorted().collect(Collectors.toList()));
        for (java.util.Map.Entry<UUID, Set<Pref>> e : prefsOff.entrySet()) {
            if (!e.getValue().isEmpty()) {
                yaml.set("prefs-off." + e.getKey(), e.getValue().stream().map(Pref::key).sorted().collect(Collectors.toList()));
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    boolean get(Toggle t) {
        return toggles.getOrDefault(t, t.fallback);
    }

    void set(Toggle t, boolean on) {
        toggles.put(t, on);
        save();
    }

    boolean isSharing() {
        return sharing;
    }

    double sharingFraction() {
        return sharingFraction;
    }

    void setSharing(boolean on, double fraction) {
        this.sharing = on;
        this.sharingFraction = Settings.clampFraction(fraction);
        save();
    }

    boolean isOptedOut(UUID id) {
        return optedOut.contains(id);
    }

    /** @return true if the status changed. */
    boolean setOptedOut(UUID id, boolean out) {
        boolean changed = out ? optedOut.add(id) : optedOut.remove(id);
        if (changed) save();
        return changed;
    }

    private void readUuids(YamlConfiguration yaml, String path, Set<UUID> into) {
        for (String raw : yaml.getStringList(path)) {
            try {
                into.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    // ── Roster ───────────────────────────────────────────

    boolean inRoster(UUID id) {
        return roster.contains(id);
    }

    Set<UUID> roster() {
        return Set.copyOf(roster);
    }

    void setRoster(java.util.Collection<UUID> ids) {
        roster.clear();
        roster.addAll(ids);
        tpaUsed.clear();
        save();
    }

    boolean addToRoster(UUID id) {
        boolean changed = roster.add(id);
        if (changed) save();
        return changed;
    }

    boolean removeFromRoster(UUID id) {
        boolean changed = roster.remove(id);
        if (changed) save();
        return changed;
    }

    /** The run is over: everyone may join the next one. */
    void clearRoster() {
        roster.clear();
        tpaUsed.clear();
        save();
    }

    void markSpectator(UUID id, boolean made) {
        if (made ? madeSpectator.add(id) : madeSpectator.remove(id)) save();
    }

    boolean wasMadeSpectator(UUID id) {
        return madeSpectator.contains(id);
    }

    // ── Teleport ─────────────────────────────────────────

    boolean hasUsedTpa(UUID id) {
        return tpaUsed.contains(id);
    }

    void useTpa(UUID id) {
        if (tpaUsed.add(id)) save();
    }

    // ── Goal, late joiners, personal prefs ───────────────

    Goal goal() {
        return goal;
    }

    void setGoal(Goal goal) {
        this.goal = goal;
        save();
    }

    LateJoin lateJoin() {
        return lateJoin;
    }

    void setLateJoin(LateJoin lateJoin) {
        this.lateJoin = lateJoin;
        save();
    }

    boolean pref(UUID id, Pref pref) {
        Set<Pref> off = prefsOff.get(id);
        return off == null || !off.contains(pref);
    }

    void setPref(UUID id, Pref pref, boolean on) {
        Set<Pref> off = prefsOff.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        if (on) off.remove(pref); else off.add(pref);
        save();
    }

    Set<UUID> optedOut() {
        return Set.copyOf(optedOut);
    }
}
