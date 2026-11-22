package dev.nora.lifeline;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.logging.Level;

/**
 * Tracks the current attempt (time alive, damage taken, mobs slain per player) and the history of
 * past wipes, saved in plugins/FateLink/stats.yml. Counters are lock-free so every Folia region
 * thread can record into them; ending a run and saving are synchronized.
 */
final class RunStats {

    static final class PlayerStat {
        volatile String name;
        final DoubleAdder damage = new DoubleAdder();
        final AtomicInteger kills = new AtomicInteger();

        PlayerStat(String name) {
            this.name = name;
        }
    }

    record Wipe(int attempt, long durationMs, long endedAt, String victim, String cause,
                List<String> taken, String topDamageName, double topDamage,
                String topKillsName, int topKills, double totalDamage, int totalKills,
                boolean record, long previousBest, boolean victory, List<Line> lines) {}

    /** One player's numbers for the stats screen. */
    record Line(String name, double damage, int kills) {
        String encode() {
            return name + "|" + damage + "|" + kills;
        }

        static Line decode(String raw) {
            String[] parts = raw.split("\\|");
            try {
                return new Line(parts[0], Double.parseDouble(parts[1]), Integer.parseInt(parts[2]));
            } catch (RuntimeException e) {
                return new Line(parts[0], 0, 0);
            }
        }
    }

    Wipe last() {
        synchronized (history) {
            return history.isEmpty() ? null : history.get(0);
        }
    }

    private final LifelinePlugin plugin;
    private final File file;

    private volatile int attempt = 1;
    /**
     * Play time of the current attempt. It only counts while the link is running and at least
     * one player is online: {@link #tick(boolean)} opens and closes "segments" once a second.
     */
    private volatile long accumulatedMs;
    private volatile long segmentStart; // 0 = not counting right now
    private volatile long bestMs;
    private final Map<UUID, PlayerStat> players = new ConcurrentHashMap<>();
    private final List<Wipe> history = Collections.synchronizedList(new ArrayList<>());

    RunStats(LifelinePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    // ── Recording (any thread) ───────────────────────────

    void recordDamage(UUID id, String name, double amount) {
        if (amount > 0) stat(id, name).damage.add(amount);
    }

    void recordKill(UUID id, String name) {
        stat(id, name).kills.incrementAndGet();
    }

    private PlayerStat stat(UUID id, String name) {
        PlayerStat s = players.computeIfAbsent(id, k -> new PlayerStat(name));
        s.name = name;
        return s;
    }

    int attempt() {
        return attempt;
    }

    long elapsedMs() {
        long seg = segmentStart;
        return accumulatedMs + (seg > 0 ? System.currentTimeMillis() - seg : 0);
    }

    /** Called once a second from the global scheduler. */
    synchronized void tick(boolean shouldCount) {
        long now = System.currentTimeMillis();
        if (shouldCount && segmentStart == 0) {
            segmentStart = now;
        } else if (!shouldCount && segmentStart > 0) {
            accumulatedMs += now - segmentStart;
            segmentStart = 0;
        }
    }

    long bestMs() {
        return bestMs;
    }

    List<Wipe> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    // ── Ending a run ─────────────────────────────────────

    /** Closes the current attempt, starts the next one and returns the summary of the wipe. */
    synchronized Wipe endRun(String victim, String cause, List<String> taken, boolean victory) {
        long now = System.currentTimeMillis();
        long duration = elapsedMs();

        String topDamageName = null, topKillsName = null;
        double topDamage = 0, totalDamage = 0;
        int topKills = 0, totalKills = 0;
        List<Line> lines = new ArrayList<>();
        for (PlayerStat s : players.values()) {
            double d = s.damage.sum();
            lines.add(new Line(s.name, d, s.kills.get()));
            int k = s.kills.get();
            totalDamage += d;
            totalKills += k;
            if (d > topDamage) { topDamage = d; topDamageName = s.name; }
            if (k > topKills) { topKills = k; topKillsName = s.name; }
        }

        long previousBest = bestMs;
        boolean record = duration > bestMs;
        if (record) bestMs = duration;

        Wipe wipe = new Wipe(attempt, duration, now, victim, cause, List.copyOf(taken),
                topDamageName, topDamage, topKillsName, topKills, totalDamage, totalKills,
                record, previousBest, victory, List.copyOf(lines));
        history.add(0, wipe);
        int keep = plugin.settings().historySize();
        while (history.size() > keep) history.remove(history.size() - 1);

        attempt++;
        accumulatedMs = 0;
        segmentStart = segmentStart > 0 ? now : 0;
        players.clear();
        save();
        return wipe;
    }

    synchronized void reset() {
        attempt = 1;
        accumulatedMs = 0;
        segmentStart = segmentStart > 0 ? System.currentTimeMillis() : 0;
        bestMs = 0;
        players.clear();
        history.clear();
        save();
    }

    // ── Persistence ──────────────────────────────────────

    synchronized void load() {
        players.clear();
        history.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        attempt = (int) Math.max(1, yaml.getLong("attempt", 1));
        accumulatedMs = yaml.getLong("elapsed-ms", 0);
        segmentStart = 0;
        bestMs = yaml.getLong("best-ms", 0);
        ConfigurationSection current = yaml.getConfigurationSection("current");
        if (current != null) {
            for (String key : current.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    PlayerStat s = new PlayerStat(current.getString(key + ".name", "?"));
                    s.damage.add(current.getDouble(key + ".damage", 0));
                    s.kills.set((int) current.getLong(key + ".kills", 0));
                    players.put(id, s);
                } catch (IllegalArgumentException ignored) {
                    // skip bad entries
                }
            }
        }

        ConfigurationSection past = yaml.getConfigurationSection("history");
        if (past != null) {
            List<Wipe> loaded = new ArrayList<>();
            for (String key : past.getKeys(false)) {
                String p = key + ".";
                loaded.add(new Wipe(
                        (int) past.getLong(p + "attempt", 0),
                        past.getLong(p + "duration-ms", 0),
                        past.getLong(p + "ended-at", 0),
                        past.getString(p + "victim", "?"),
                        past.getString(p + "cause", ""),
                        past.getStringList(p + "taken"),
                        past.getString(p + "top-damage-name", null),
                        past.getDouble(p + "top-damage", 0),
                        past.getString(p + "top-kills-name", null),
                        (int) past.getLong(p + "top-kills", 0),
                        past.getDouble(p + "total-damage", 0),
                        (int) past.getLong(p + "total-kills", 0),
                        past.getBoolean(p + "record", false),
                        past.getLong(p + "previous-best", 0),
                        past.getBoolean(p + "victory", false),
                        past.getStringList(p + "players").stream().map(Line::decode).toList()));
            }
            loaded.sort((a, b) -> Integer.compare(b.attempt(), a.attempt()));
            history.addAll(loaded);
        }
    }

    void save() {
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> saveNow());
    }

    synchronized void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("attempt", attempt);
        yaml.set("elapsed-ms", elapsedMs());
        yaml.set("best-ms", bestMs);
        for (Map.Entry<UUID, PlayerStat> e : players.entrySet()) {
            String p = "current." + e.getKey() + ".";
            yaml.set(p + "name", e.getValue().name);
            yaml.set(p + "damage", e.getValue().damage.sum());
            yaml.set(p + "kills", e.getValue().kills.get());
        }
        for (Wipe w : history()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempt", w.attempt());
            m.put("duration-ms", w.durationMs());
            m.put("ended-at", w.endedAt());
            m.put("victim", w.victim());
            m.put("cause", w.cause());
            m.put("taken", w.taken());
            m.put("top-damage-name", w.topDamageName());
            m.put("top-damage", w.topDamage());
            m.put("top-kills-name", w.topKillsName());
            m.put("top-kills", w.topKills());
            m.put("total-damage", w.totalDamage());
            m.put("total-kills", w.totalKills());
            m.put("victory", w.victory());
            m.put("players", w.lines().stream().map(Line::encode).toList());
            m.put("record", w.record());
            m.put("previous-best", w.previousBest());
            for (Map.Entry<String, Object> e : new HashMap<>(m).entrySet()) {
                yaml.set("history.a" + w.attempt() + "." + e.getKey(), e.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", e);
        }
    }

    /** 1:23:45 style, for the tab list and /timer. */
    static String formatClock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, sec);
        if (m > 0) return String.format("%dm %02ds", m, sec);
        return sec + "s";
    }
}
