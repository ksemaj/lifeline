package dev.nora.lifeline;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches every player's health on that player's own scheduler (Folia-safe: each task only reads
 * and messages its own player). Low players are published to a shared map, and every linked
 * player's task renders the shared map onto their own action bar.
 */
final class HealthWatcher {

    /** A snapshot of a player in danger, taken on their own thread so others can read it safely. */
    private record LowEntry(String name, double health, long since,
                            UUID world, String worldName, double x, double y, double z) {}

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private static final TextColor WARN = TextColor.color(0xF59E0B);
    private static final TextColor CRITICAL_A = TextColor.color(0xEF4444);
    private static final TextColor CRITICAL_B = TextColor.color(0x7F1D1D);

    private final LifelinePlugin plugin;
    private final Map<UUID, LowEntry> low = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertSeen = new ConcurrentHashMap<>();
    private final Map<UUID, Object> running = new ConcurrentHashMap<>();
    /** Players whose compass item we re-pointed, so we can put it back on spawn later. */
    private final Set<UUID> compassPointed = ConcurrentHashMap.newKeySet();

    HealthWatcher(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    void start(Player player) {
        UUID id = player.getUniqueId();
        Object token = new Object();
        if (running.putIfAbsent(id, token) != null) return;
        long interval = plugin.settings().checkIntervalTicks();
        lastAlertSeen.putIfAbsent(id, System.currentTimeMillis());
        // The retired callback runs if the player entity goes away; only clean up our own run.
        player.getScheduler().runAtFixedRate(plugin, task -> tick(player),
                () -> { if (running.remove(id, token)) low.remove(id); }, 1L, interval);
    }

    /** Drop a player from the warning list straight away (they died). */
    void clearLow(UUID id) {
        low.remove(id);
    }

    void forget(UUID id) {
        running.remove(id);
        low.remove(id);
        lastAlertSeen.remove(id);
        compassPointed.remove(id);
        plugin.display().forget(id);
        plugin.activity().forget(id);
    }

    /** True if anyone linked is currently on low health. */
    boolean anyoneLow() {
        return !low.isEmpty();
    }

    private void tick(Player player) {
        UUID id = player.getUniqueId();
        plugin.activity().tick(player);
        if (!player.isOnline()) return; // kicked for being AFK
        plugin.display().updateTab(player);
        plugin.display().updateNametag(player);
        Settings settings = plugin.settings();
        double warnAt = settings.warnAt();
        double criticalAt = settings.criticalAt();
        boolean linked = plugin.isLinked(player);

        // 1. Publish this player's own state.
        double health = player.getHealth();
        Location here = player.getLocation();
        if (linked && !player.isDead() && health > 0 && health <= warnAt) {
            World w = here.getWorld();
            low.compute(id, (k, old) -> new LowEntry(player.getName(), health,
                    old == null ? System.currentTimeMillis() : old.since(),
                    w.getUID(), w.getName(), here.getX(), here.getY(), here.getZ()));
        } else {
            low.remove(id);
        }

        // 2. Show everyone's state to this player.
        FateStore store = plugin.store();
        if (!linked || low.isEmpty() || !store.pref(id, Pref.WARNINGS)) {
            resetCompass(player);
            return;
        }
        LowEntry nearest = null;
        double nearestDist = Double.MAX_VALUE;

        List<Map.Entry<UUID, LowEntry>> entries = new ArrayList<>(low.entrySet());
        entries.sort(Comparator.comparingDouble(e -> e.getValue().health()));

        boolean arrows = store.get(Toggle.COMPASS) && store.pref(id, Pref.COMPASS);
        boolean pulse = (player.getTicksLived() / 10) % 2 == 0;
        List<Component> parts = new ArrayList<>();
        long newest = 0;
        for (Map.Entry<UUID, LowEntry> e : entries) {
            LowEntry entry = e.getValue();
            newest = Math.max(newest, entry.since());
            boolean critical = entry.health() <= criticalAt;
            TextColor color = critical ? (pulse ? CRITICAL_A : CRITICAL_B) : WARN;
            boolean self = e.getKey().equals(id);
            Component compass = Component.empty();
            if (!self) {
                boolean sameWorld = entry.world().equals(here.getWorld().getUID());
                if (sameWorld) {
                    double dx = entry.x() - here.getX(), dz = entry.z() - here.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist < nearestDist) { nearestDist = dist; nearest = entry; }
                    if (arrows) {
                        compass = plugin.msg("compass",
                                Placeholder.unparsed("arrow", arrow(here.getYaw(), dx, dz)),
                                Placeholder.unparsed("distance", String.valueOf(Math.round(dist))));
                    }
                } else if (arrows) {
                    compass = plugin.msg("compass-other-world", Placeholder.unparsed("world", prettyWorld(entry.worldName())));
                }
            }
            Component part = self
                    ? plugin.msg("low-health-self", Placeholder.unparsed("hearts", LifelinePlugin.hearts(entry.health())))
                    : plugin.msg("low-health-other",
                        Placeholder.unparsed("name", entry.name()),
                        Placeholder.unparsed("hearts", LifelinePlugin.hearts(entry.health())),
                        Placeholder.component("compass", compass));
            part = part.colorIfAbsent(color);
            if (critical) part = part.decorationIfAbsent(TextDecoration.BOLD, TextDecoration.State.TRUE);
            parts.add(part);
        }

        Component joined = Component.join(JoinConfiguration.separator(plugin.msg("low-health-separator")), parts);
        player.sendActionBar(plugin.msg("low-health-bar", Placeholder.component("entries", joined))
                .colorIfAbsent(NamedTextColor.GOLD));

        // Point real compasses at the nearest player in danger in this world.
        if (store.get(Toggle.COMPASS_ITEM) && store.pref(id, Pref.COMPASS) && nearest != null) {
            player.setCompassTarget(new Location(here.getWorld(), nearest.x(), nearest.y(), nearest.z()));
            compassPointed.add(id);
        } else {
            resetCompass(player);
        }

        // Heartbeat when someone new starts bleeding out.
        Long seen = lastAlertSeen.get(id);
        if (seen != null && newest > seen) {
            lastAlertSeen.put(id, newest);
            if (store.get(Toggle.ALERT_SOUND) && store.pref(id, Pref.HEARTBEAT)) {
                Sound s = plugin.sound("low-health", 1.0f, 0.6f);
                if (s != null) player.playSound(s);
            }
        }
    }

    private void resetCompass(Player player) {
        if (compassPointed.remove(player.getUniqueId())) {
            player.setCompassTarget(player.getWorld().getSpawnLocation());
        }
    }

    /**
     * Arrow from the viewer's facing to the target. Minecraft yaw: 0 = south (+z), 90 = west (-x),
     * growing clockwise, so the target's yaw is -atan2(dx, dz).
     */
    static String arrow(float viewerYaw, double dx, double dz) {
        double targetYaw = Math.toDegrees(-Math.atan2(dx, dz));
        double rel = ((targetYaw - viewerYaw) % 360 + 540) % 360 - 180; // -180..180, + = to the right
        int index = (int) Math.round(rel / 45.0);
        return ARROWS[((index % 8) + 8) % 8];
    }

    private static String prettyWorld(String name) {
        if (name.endsWith("_nether")) return "Nether";
        if (name.endsWith("_the_end")) return "The End";
        return name;
    }
}
