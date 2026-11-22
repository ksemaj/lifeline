package dev.nora.lifeline;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Fresh world after a wipe, done with a restart so it works the same on Paper and Folia:
 * <ol>
 *   <li>Countdown, then everyone is kicked and the server shuts down.</li>
 *   <li>Before shutting down, a marker file is written and server.properties gets a new seed.</li>
 *   <li>On the next boot, {@link #applyPending} runs in onLoad, before any world is loaded, and
 *       moves the old world folders into world-backups/ (or deletes them).</li>
 * </ol>
 * The server only comes back by itself if it runs in a restart loop (see start.sh / start.bat).
 */
final class WorldReset {

    private static final String MARKER = "reset-pending.yml";

    private final LifelinePlugin plugin;
    private volatile boolean inProgress;

    WorldReset(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    boolean inProgress() {
        return inProgress;
    }

    /** Start the countdown. Safe to call from any thread. */
    void schedule(long delayTicks) {
        if (inProgress) return;
        inProgress = true;
        // Nobody can die into another cascade while the world is about to go.
        plugin.store().setEnabled(false);
        plugin.stats().tick(false);

        int seconds = plugin.settings().resetCountdownSeconds();
        Bukkit.getServer().sendMessage(plugin.prefixed("reset-announce",
                Placeholder.unparsed("seconds", String.valueOf(seconds + delayTicks / 20))));
        for (int i = seconds; i >= 1; i--) {
            int left = i;
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                Title title = Title.title(plugin.msg("reset-title"),
                        plugin.msg("reset-subtitle", Placeholder.unparsed("seconds", String.valueOf(left))),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
                Sound tick = plugin.sound("countdown", 1.0f, 0.5f);
                plugin.forEachPlayer(p -> {
                    p.showTitle(title);
                    if (tick != null) p.playSound(tick);
                });
            }, Math.max(1, delayTicks + (long) (seconds - i) * 20L));
        }
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> resetNow(),
                Math.max(1, delayTicks + seconds * 20L));
    }

    private void resetNow() {
        Logger log = plugin.getLogger();
        try {
            writeMarker();
            if (plugin.settings().resetNewSeed()) setRandomSeed();
        } catch (IOException e) {
            log.severe("World reset aborted, could not prepare it: " + e);
            inProgress = false;
            Bukkit.getServer().sendMessage(plugin.prefixed("reset-failed"));
            return;
        }
        // The new world waits for /lifeline start; whoever is online then makes the new roster.
        plugin.store().setEnabled(false);
        plugin.store().clearRoster();
        plugin.store().saveNow();
        plugin.stats().saveNow();

        Component reason = plugin.msg("reset-kick");
        plugin.forEachPlayer(p -> p.kick(reason));
        log.info("World reset: shutting down. Old worlds are cleared on the next start.");
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> Bukkit.shutdown(), 40L);
    }

    private void writeMarker() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("worlds", worldFolders(levelName()));
        yaml.set("attempt", plugin.stats().attempt());
        yaml.set("requested-at", System.currentTimeMillis());
        plugin.getDataFolder().mkdirs();
        yaml.save(new File(plugin.getDataFolder(), MARKER));
    }

    /** level-name plus the Nether/End folders older layouts keep beside it. */
    private static List<String> worldFolders(String level) {
        return List.of(level, level + "_nether", level + "_the_end");
    }

    private static String levelName() throws IOException {
        Properties props = readProperties();
        String name = props.getProperty("level-name", "world").trim();
        if (name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IOException("Refusing unusual level-name '" + name + "'");
        }
        return name;
    }

    private static Properties readProperties() throws IOException {
        Properties props = new Properties();
        File file = new File("server.properties");
        if (file.exists()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                props.load(in);
            }
        }
        return props;
    }

    /** Rewrites only the level-seed line, leaving the rest of server.properties as it was. */
    private static void setRandomSeed() throws IOException {
        Path path = Path.of("server.properties");
        if (!Files.exists(path)) return;
        String seed = String.valueOf(ThreadLocalRandom.current().nextLong());
        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.ISO_8859_1));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("level-seed=")) {
                lines.set(i, "level-seed=" + seed);
                found = true;
            }
        }
        if (!found) lines.add("level-seed=" + seed);
        Files.write(path, lines, StandardCharsets.ISO_8859_1);
    }

    // ── Next boot (onLoad: no worlds are loaded yet) ─────

    static void applyPending(File dataFolder, File worldContainer, int keepBackups, Logger log) {
        File marker = new File(dataFolder, MARKER);
        if (!marker.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(marker);
        List<String> worlds = yaml.getStringList("worlds");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        long attempt = yaml.getLong("attempt", 0);
        Path root = worldContainer.toPath().toAbsolutePath().normalize();
        Path backups = root.resolve("world-backups");

        int moved = 0;
        boolean failed = false;
        for (String name : worlds) {
            Path folder = root.resolve(name).normalize();
            if (!folder.startsWith(root) || folder.equals(root) || !Files.isDirectory(folder)) continue;
            try {
                if (keepBackups > 0) {
                    Files.createDirectories(backups);
                    Path target = backups.resolve(name + "-before-attempt-" + attempt + "-" + stamp);
                    Files.move(folder, target, StandardCopyOption.ATOMIC_MOVE);
                    moved++;
                    log.info("World reset: moved " + name + " to world-backups/" + target.getFileName());
                } else {
                    deleteTree(folder);
                    log.info("World reset: deleted " + name);
                }
            } catch (IOException e) {
                log.severe("World reset: could not clear " + name + ": " + e);
                failed = true;
            }
        }
        // Keep the newest `keepBackups` resets' worth of folders.
        if (keepBackups > 0 && moved > 0) pruneBackups(backups, keepBackups * moved, log);
        if (failed) {
            // Keep the marker so the next start tries again (e.g. a file was locked).
            log.severe("World reset incomplete: the old world will load this time. Stop the server, "
                    + "close anything using the world folder, and start again to retry.");
            return;
        }
        if (!marker.delete()) log.warning("World reset: could not delete " + MARKER);
    }

    private static void pruneBackups(Path backups, int keep, Logger log) {
        if (!Files.isDirectory(backups)) return;
        try (Stream<Path> entries = Files.list(backups)) {
            List<Path> all = new ArrayList<>(entries.filter(Files::isDirectory).toList());
            all.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            for (Path old : all.subList(Math.min(keep, all.size()), all.size())) {
                deleteTree(old);
                log.info("World reset: removed old backup " + old.getFileName());
            }
        } catch (IOException e) {
            log.warning("World reset: could not prune backups: " + e);
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
