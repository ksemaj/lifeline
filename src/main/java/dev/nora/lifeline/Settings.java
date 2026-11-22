package dev.nora.lifeline;

import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable snapshot of config.yml. Bukkit's config object isn't thread-safe, and on Folia every
 * region thread reads settings at once, so the plugin swaps in a fresh snapshot on reload instead.
 */
record Settings(
        Set<GameMode> affectedModes,
        long cascadeDelayTicks,
        boolean lightningEffect,
        long cascadeCooldownMs,
        double warnAt,
        double criticalAt,
        long checkIntervalTicks,
        boolean alertSound,
        boolean compass,
        boolean compassItem,
        boolean sharingDefault,
        double sharingFractionDefault,
        boolean statsEnabled,
        long summaryDelayTicks,
        int historySize,
        long combatMs,
        long afkFlagMs,
        long afkKickMs,
        int resetCountdownSeconds,
        long resetDelayTicks,
        boolean resetNewSeed,
        long logoutGraceMs,
        long barSeconds,
        boolean blockOptOutWhileLow,
        long optCooldownMs,
        Map<String, String> sounds,
        Map<String, String> messages
) {

    static Settings from(FileConfiguration config, Logger logger) {
        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (String raw : config.getStringList("affected-gamemodes")) {
            try {
                modes.add(GameMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("Unknown game mode in config: " + raw);
            }
        }
        return new Settings(
                Set.copyOf(modes),
                Math.max(1, config.getLong("cascade.delay-ticks", 10)),
                config.getBoolean("cascade.lightning-effect", true),
                Math.max(0, config.getLong("cascade.cooldown-seconds", 5)) * 1000L,
                config.getDouble("low-health.warn-at", 6.0),
                config.getDouble("low-health.critical-at", 3.0),
                Math.max(1, config.getLong("low-health.check-interval-ticks", 10)),
                config.getBoolean("low-health.alert-sound", true),
                config.getBoolean("low-health.compass", true),
                config.getBoolean("low-health.compass-item", true),
                config.getBoolean("damage-sharing.enabled", false),
                clampFraction(config.getDouble("damage-sharing.fraction", 0.25)),
                config.getBoolean("stats.enabled", true),
                Math.max(1, config.getLong("stats.summary-delay-ticks", 60)),
                (int) Math.max(0, config.getLong("stats.history-size", 20)),
                Math.max(1, config.getLong("protection.combat-seconds", 10)) * 1000L,
                Math.max(1, config.getLong("afk.flag-minutes", 5)) * 60_000L,
                Math.max(1, config.getLong("afk.kick-minutes", 15)) * 60_000L,
                (int) Math.max(1, Math.min(60, config.getLong("world-reset.countdown-seconds", 3))),
                Math.max(1, config.getLong("world-reset.delay-seconds", 8)) * 20L,
                config.getBoolean("world-reset.new-seed", true),
                Math.max(0, config.getLong("protection.grace-seconds", 30)) * 1000L,
                Math.max(5, config.getLong("stats.bar-seconds", 90)),
                config.getBoolean("opt-out.block-while-low", true),
                Math.max(0, config.getLong("opt-out.cooldown-seconds", 30)) * 1000L,
                strings(config.getConfigurationSection("sounds")),
                strings(config.getConfigurationSection("messages")));
    }

    static double clampFraction(double f) {
        return Math.max(0.01, Math.min(1.0, f));
    }

    private static Map<String, String> strings(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) out.put(key, value);
            }
        }
        return Map.copyOf(out);
    }
}
