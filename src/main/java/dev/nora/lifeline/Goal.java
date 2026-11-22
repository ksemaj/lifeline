package dev.nora.lifeline;

/** What wins a run. */
enum Goal {
    DRAGON("Ender Dragon"),
    WITHER("Wither"),
    EITHER("Dragon or Wither"),
    NONE("None (endless)");

    final String label;

    Goal(String label) {
        this.label = label;
    }

    Goal next() {
        return values()[(ordinal() + 1) % values().length];
    }

    static Goal parse(String raw, Goal fallback) {
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
