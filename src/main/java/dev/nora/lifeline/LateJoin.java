package dev.nora.lifeline;

/** What happens to someone who wasn't online when the run started. */
enum LateJoin {
    SPECTATE("Watch as a spectator"),
    BLOCK("Can't join until the run ends");

    final String label;

    LateJoin(String label) {
        this.label = label;
    }

    LateJoin next() {
        return values()[(ordinal() + 1) % values().length];
    }

    static LateJoin parse(String raw, LateJoin fallback) {
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
