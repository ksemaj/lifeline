package dev.nora.lifeline;

/**
 * Server-wide switches admins flip in /lifeline settings. Saved in data.yml; config.yml only gives
 * the defaults. "Rules" change how the game plays for everyone, so changes to them are announced
 * to all players and listed in /lifeline rules. The rest only change what's shown.
 */
enum Toggle {
    // ── Gameplay rules ──
    COMBAT_LOG("protection.combat-log", true, true, "Combat-log deaths",
            "Logging out mid-fight or on critical", "health counts as dying.",
            "Logging out mid-fight or on critical health counts as dying.", "Logging out is always safe."),
    AFK_KICK("afk.kick", true, true, "AFK kick",
            "Kick players idle for a long time", "during a run.",
            "Players idle too long during a run get kicked.", "Nobody is kicked for being AFK."),
    AFK_PROTECT("afk.mobs-ignore", true, true, "Mobs ignore AFK players",
            "Hostile mobs won't target players", "flagged AFK.",
            "Hostile mobs ignore players who are AFK.", "Mobs attack AFK players like anyone else."),
    TPA("tpa.enabled", false, true, "One teleport each",
            "Everyone gets one /tpa per run.", "The other player clicks to accept.",
            "Everyone gets one /tpa per run.", "/tpa is off."),
    COORDS_CMD("coords.enabled", true, true, "/coords",
            "Players can post their", "coordinates in chat.",
            "/coords posts your coordinates in chat.", "/coords is off."),
    WORLD_RESET("world-reset.enabled", false, true, "New world after wipe",
            "After a wipe: kick everyone, restart,", "and start on a fresh world and seed.",
            "After a wipe the server restarts on a brand-new world.", "The world stays after a wipe."),
    AUTO_CONTINUE("cascade.auto-continue", true, true, "Auto-continue after wipe",
            "Same world: the next attempt starts", "right away instead of waiting.",
            "After a wipe the next attempt starts right away.", "After a wipe the next attempt waits for an admin."),
    // ── Display (server-wide) ──
    COMPASS("low-health.compass", true, false, "Compass arrows",
            "Arrow and distance to players", "in danger on the action bar.", null, null),
    COMPASS_ITEM("low-health.compass-item", true, false, "Compass item tracking",
            "Compass items point at the", "nearest player in danger.", null, null),
    ALERT_SOUND("low-health.alert-sound", true, false, "Heartbeat alert",
            "Sound when someone new", "drops to low health.", null, null),
    LIGHTNING("cascade.lightning-effect", true, false, "Lightning on wipe",
            "Harmless lightning on everyone", "who falls with the first.", null, null),
    STATS_SUMMARY("stats.enabled", true, false, "Results bar & stats",
            "Results bar and chat card", "after every wipe or win.", null, null),
    TAB_INFO("tab-list.enabled", true, false, "Tab list info",
            "Hearts and dimension in the tab", "list, attempt and timer on top.", null, null),
    NAMETAG("nametag.enabled", true, false, "Hearts & AFK by names",
            "Hearts and afk next to names", "above heads (Paper only).", null, null),
    AFK_FLAG("afk.flag", true, false, "AFK tag",
            "Mark idle players afk in the", "tab list and above their head.", null, null);

    final String configPath;
    final boolean fallback;
    /** True for rules that change gameplay for everyone. */
    final boolean rule;
    final String label;
    final String line1;
    final String line2;
    /** Plain sentences for /lifeline rules and change announcements (rules only). */
    final String whenOn;
    final String whenOff;

    Toggle(String configPath, boolean fallback, boolean rule, String label, String line1, String line2,
           String whenOn, String whenOff) {
        this.configPath = configPath;
        this.fallback = fallback;
        this.rule = rule;
        this.label = label;
        this.line1 = line1;
        this.line2 = line2;
        this.whenOn = whenOn;
        this.whenOff = whenOff;
    }

    String dataKey() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
