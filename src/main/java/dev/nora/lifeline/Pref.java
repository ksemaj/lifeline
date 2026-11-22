package dev.nora.lifeline;

/** Per-player display preferences (/lifeline settings for everyone). Server rules stay in {@link Toggle}. */
enum Pref {
    WARNINGS("Low-health warnings", "Action bar when someone tied in is low."),
    COMPASS("Compass arrows", "Arrow and distance to players in danger."),
    HEARTBEAT("Heartbeat sound", "Sound when someone drops low."),
    TAB_INFO("Tab header & timer", "Attempt and timer in your tab list.");

    final String label;
    final String description;

    Pref(String label, String description) {
        this.label = label;
        this.description = description;
    }

    String key() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
