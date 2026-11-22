package dev.nora.lifeline;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class FateCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBS = List.of("status", "list", "rules", "settings", "stats", "timer", "optout", "optin");
    private static final List<String> ADMIN_SUBS = List.of("start", "stop", "add", "remove", "sharing", "compass", "reset", "resetstats", "reload");

    private final LifelinePlugin plugin;
    private final Map<UUID, Long> lastToggle = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCoords = new ConcurrentHashMap<>();

    FateCommand(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /timer and /stats are their own commands too.
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("timer")) {
            timer(sender);
            return true;
        }
        if (name.equals("stats")) {
            stats(sender);
            return true;
        }
        if (name.equals("coords")) {
            coords(sender);
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "list" -> list(sender);
            case "rules" -> rules(sender);
            case "add" -> rosterCommand(sender, args, true);
            case "remove" -> rosterCommand(sender, args, false);
            case "optout", "leave" -> optCommand(sender, args, true);
            case "optin", "join" -> optCommand(sender, args, false);
            case "start", "enable" -> {
                if (!checkAdmin(sender)) return true;
                if (plugin.store().isEnabled() || plugin.isCountingDown()) {
                    sender.sendMessage(plugin.prefixed("already-started",
                            Placeholder.unparsed("attempt", String.valueOf(plugin.stats().attempt())),
                            Placeholder.unparsed("time", RunStats.formatDuration(plugin.stats().elapsedMs()))));
                    return true;
                }
                // "/lifeline start now" skips the countdown.
                boolean countdown = !(args.length >= 2 && args[1].equalsIgnoreCase("now"));
                plugin.startRun(countdown);
            }
            case "stop", "disable" -> {
                if (!checkAdmin(sender)) return true;
                if (!plugin.store().isEnabled() && !plugin.isCountingDown()) {
                    sender.sendMessage(plugin.prefixed("not-started"));
                    return true;
                }
                plugin.stopRun();
            }
            case "timer", "time" -> timer(sender);
            case "reset" -> {
                if (!checkAdmin(sender)) return true;
                if (plugin.worldReset().inProgress()) {
                    sender.sendMessage(plugin.prefixed("reset-already"));
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                    // A manual reset ends the attempt: it's recorded, and the new world waits for /lifeline start.
                    if (plugin.store().isEnabled() || plugin.stats().elapsedMs() > 0) {
                        plugin.stats().endRun(sender.getName(), "reset the world", List.of(), false);
                    }
                    plugin.endRun();
                    plugin.worldReset().schedule(1L);
                } else {
                    sender.sendMessage(plugin.prefixed("reset-confirm"));
                }
            }
            case "stats" -> stats(sender);
            case "settings", "gui", "menu" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.prefixed("players-only"));
                } else if (p.hasPermission("lifeline.admin")) {
                    new FateMenu(plugin, FateMenu.Kind.SERVER, p).open();
                } else if (p.hasPermission("lifeline.use")) {
                    new FateMenu(plugin, FateMenu.Kind.PERSONAL, p).open();
                } else {
                    p.sendMessage(plugin.prefixed("no-permission"));
                }
            }
            case "me", "prefs" -> {
                if (sender instanceof Player p && p.hasPermission("lifeline.use")) new FateMenu(plugin, FateMenu.Kind.PERSONAL, p).open();
                else sender.sendMessage(plugin.prefixed("players-only"));
            }
            case "compass" -> {
                if (!checkAdmin(sender)) return true;
                boolean on = args.length >= 2 ? args[1].equalsIgnoreCase("on") : !plugin.store().get(Toggle.COMPASS);
                plugin.store().set(Toggle.COMPASS, on);
                plugin.store().set(Toggle.COMPASS_ITEM, on);
                sender.sendMessage(plugin.prefixed(on ? "compass-on" : "compass-off"));
            }
            case "sharing" -> {
                if (!checkAdmin(sender)) return true;
                sharing(sender, args);
            }
            case "resetstats" -> {
                if (!checkAdmin(sender)) return true;
                plugin.stats().reset();
                sender.sendMessage(plugin.prefixed("stats-reset"));
            }
            case "reload" -> {
                if (!checkAdmin(sender)) return true;
                plugin.reload();
                sender.sendMessage(plugin.prefixed("reloaded"));
            }
            default -> sender.sendMessage(plugin.prefixed("usage"));
        }
        return true;
    }

    private void timer(CommandSender sender) {
        if (!sender.hasPermission("lifeline.use")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        RunStats stats = plugin.stats();
        String key = plugin.store().isEnabled() ? "timer" : "timer-paused";
        sender.sendMessage(plugin.prefixed(key,
                Placeholder.unparsed("attempt", String.valueOf(stats.attempt())),
                Placeholder.unparsed("time", RunStats.formatClock(stats.elapsedMs())),
                Placeholder.unparsed("best", stats.bestMs() > 0 ? RunStats.formatClock(stats.bestMs()) : "none yet")));
    }

    private void stats(CommandSender sender) {
        if (!sender.hasPermission("lifeline.use")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        RunStats stats = plugin.stats();
        List<RunStats.Wipe> history = stats.history();
        // Last run's full breakdown first, then the current attempt and recent history.
        if (stats.last() != null) {
            plugin.statsScreen().details(stats.last()).forEach(sender::sendMessage);
            sender.sendMessage(Component.empty());
        }
        long best = stats.bestMs();
        sender.sendMessage(plugin.prefixed("stats-current",
                Placeholder.unparsed("attempt", String.valueOf(stats.attempt())),
                Placeholder.unparsed("time", RunStats.formatDuration(stats.elapsedMs())),
                Placeholder.unparsed("best", best > 0 ? RunStats.formatDuration(best) : "none yet"),
                Placeholder.unparsed("wipes", String.valueOf(stats.attempt() - 1))));
        for (RunStats.Wipe w : history.subList(0, Math.min(5, history.size()))) {
            sender.sendMessage(plugin.msg("stats-history-line",
                    Placeholder.unparsed("attempt", String.valueOf(w.attempt())),
                    Placeholder.unparsed("time", RunStats.formatDuration(w.durationMs())),
                    Placeholder.unparsed("victim", w.victim()),
                    Placeholder.unparsed("cause", w.cause())));
        }
    }

    private void sharing(CommandSender sender, String[] args) {
        FateStore store = plugin.store();
        String percent = String.valueOf(Math.round(store.sharingFraction() * 100));
        if (args.length < 2) {
            Component state = store.isSharing()
                    ? Component.text("on", NamedTextColor.RED)
                    : Component.text("off", NamedTextColor.GRAY);
            sender.sendMessage(plugin.prefixed("sharing-status",
                    Placeholder.component("state", state), Placeholder.unparsed("percent", percent)));
            return;
        }
        String arg = args[1].toLowerCase(Locale.ROOT).replace("%", "");
        switch (arg) {
            case "on" -> store.setSharing(true, store.sharingFraction());
            case "off" -> store.setSharing(false, store.sharingFraction());
            default -> {
                double value;
                try {
                    value = Double.parseDouble(arg);
                } catch (NumberFormatException e) {
                    sender.sendMessage(plugin.prefixed("sharing-invalid"));
                    return;
                }
                if (value > 1) value /= 100.0; // "25" means 25%; "0.25" also works
                if (value <= 0 || value > 1) {
                    sender.sendMessage(plugin.prefixed("sharing-invalid"));
                    return;
                }
                store.setSharing(true, value);
            }
        }
        String now = String.valueOf(Math.round(store.sharingFraction() * 100));
        Bukkit.getServer().sendMessage(plugin.prefixed(store.isSharing() ? "sharing-on" : "sharing-off",
                Placeholder.unparsed("percent", now)));
    }

    private void status(CommandSender sender) {
        if (!sender.hasPermission("lifeline.use")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        Component state = plugin.store().isEnabled()
                ? Component.text("active", NamedTextColor.GREEN)
                : Component.text("dormant", NamedTextColor.RED);
        Component you;
        if (sender instanceof Player p) {
            if (plugin.store().isOptedOut(p.getUniqueId())) you = Component.text("opted out", NamedTextColor.YELLOW);
            else if (plugin.store().isEnabled() && !plugin.store().inRoster(p.getUniqueId()))
                you = Component.text("watching this run", NamedTextColor.GRAY);
            else you = Component.text("tied in", NamedTextColor.WHITE);
        } else {
            you = Component.text("the console", NamedTextColor.GRAY);
        }
        // Thread-safe count (opt-in list only); game modes belong to other regions on Folia.
        long linked = Bukkit.getOnlinePlayers().stream().filter(plugin::isOptedIn).count();
        sender.sendMessage(plugin.prefixed("status",
                Placeholder.component("state", state),
                Placeholder.component("you", you),
                Placeholder.unparsed("linked", String.valueOf(linked))));
    }

    private void list(CommandSender sender) {
        if (!sender.hasPermission("lifeline.use")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        List<Component> in = new ArrayList<>();
        List<Component> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean playing = plugin.store().isEnabled() ? plugin.isOptedIn(p) : !plugin.store().isOptedOut(p.getUniqueId());
            if (playing) in.add(p.name().color(NamedTextColor.WHITE));
            else out.add(p.name().color(NamedTextColor.YELLOW));
        }
        Component sep = Component.text(", ", NamedTextColor.DARK_GRAY);
        Component none = Component.text("nobody", NamedTextColor.DARK_GRAY);
        sender.sendMessage(plugin.prefixed("list-header",
                Placeholder.unparsed("count", String.valueOf(in.size())),
                Placeholder.component("names", in.isEmpty() ? none : Component.join(JoinConfiguration.separator(sep), in))));
        sender.sendMessage(plugin.prefixed("list-out-header",
                Placeholder.unparsed("count", String.valueOf(out.size())),
                Placeholder.component("names", out.isEmpty() ? none : Component.join(JoinConfiguration.separator(sep), out))));
    }

    private void optCommand(CommandSender sender, String[] args, boolean out) {
        if (args.length >= 2) {
            // Admin acting on someone else.
            if (!checkAdmin(sender)) return;
            OfflinePlayer target = findPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.prefixed("player-not-found", Placeholder.unparsed("player", args[1])));
                return;
            }
            plugin.store().setOptedOut(target.getUniqueId(), out);
            // During a run an admin opt-in/out also changes the roster (same as /lifeline add|remove).
            if (out) plugin.store().removeFromRoster(target.getUniqueId());
            else if (plugin.store().isEnabled()) plugin.store().addToRoster(target.getUniqueId());
            String name = target.getName() == null ? args[1] : target.getName();
            sender.sendMessage(plugin.prefixed(out ? "opted-out-other" : "opted-in-other",
                    Placeholder.unparsed("player", name)));
            Player online = target.getPlayer();
            if (online != null && !out && plugin.store().isEnabled()) {
                online.getScheduler().run(plugin, t -> plugin.releaseSpectator(online), null);
            }
            if (online != null && online != sender) {
                notify(online, out, out ? "opted-out-by-admin" : "opted-in-by-admin");
            }
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefixed("players-only"));
            return;
        }
        if (!player.hasPermission("lifeline.toggle")) {
            player.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        boolean currentlyOut = plugin.store().isOptedOut(player.getUniqueId());
        if (currentlyOut == out) {
            player.sendMessage(plugin.prefixed(out ? "already-out" : "already-in"));
            return;
        }
        // Only between runs: once a run starts, everyone in it is in until it ends.
        if (plugin.store().isEnabled() || plugin.isCountingDown()) {
            player.sendMessage(plugin.prefixed(out ? "optout-locked" : "optin-locked"));
            return;
        }
        boolean admin = player.hasPermission("lifeline.admin");
        if (!admin) {
            long cooldownMs = plugin.settings().optCooldownMs();
            long now = System.currentTimeMillis();
            long last = lastToggle.getOrDefault(player.getUniqueId(), 0L);
            if (now - last < cooldownMs) {
                long left = (cooldownMs - (now - last) + 999) / 1000;
                player.sendMessage(plugin.prefixed("on-cooldown", Placeholder.unparsed("seconds", String.valueOf(left))));
                return;
            }
            lastToggle.put(player.getUniqueId(), now);
        }
        plugin.store().setOptedOut(player.getUniqueId(), out);
        notify(player, out, out ? "opted-out-self" : "opted-in-self");
    }

    /** Title, sound and chat line for the affected player, run on their own thread (Folia-safe). */
    private void notify(Player player, boolean out, String messageKey) {
        Title title = Title.title(
                plugin.msg(out ? "opt-title-out" : "opt-title-in"),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(600)));
        Sound sound = plugin.sound(out ? "opt-out" : "opt-in", 1.0f, 1.0f);
        Component chat = plugin.prefixed(messageKey);
        player.getScheduler().run(plugin, task -> {
            player.showTitle(title);
            if (sound != null) player.playSound(sound);
            player.sendMessage(chat);
        }, null);
    }

    /** /lifeline rules: every gameplay rule in plain words, so nobody has to guess. */
    private void rules(CommandSender sender) {
        FateStore store = plugin.store();
        sender.sendMessage(plugin.prefixed("rules-header"));
        sender.sendMessage(plugin.msg("rules-line", Placeholder.unparsed("text", "If one player dies, everyone in the run dies.")));
        sender.sendMessage(plugin.msg("rules-line", Placeholder.unparsed("text", store.isSharing()
                ? "Damage sharing: everyone takes " + Math.round(store.sharingFraction() * 100) + "% of every hit."
                : "Health isn't shared: only deaths are.")));
        for (Toggle t : Toggle.values()) {
            if (!t.rule || t.whenOn == null) continue;
            sender.sendMessage(plugin.msg("rules-line", Placeholder.unparsed("text", store.get(t) ? t.whenOn : t.whenOff)));
        }
        sender.sendMessage(plugin.msg("rules-line", Placeholder.unparsed("text", "Win: " + store.goal().label
                + ". Late joiners: " + store.lateJoin().label.toLowerCase(Locale.ROOT) + ".")));
        sender.sendMessage(plugin.msg("rules-footer"));
    }

    /** /lifeline add|remove <player>: change the roster of the run that's going. */
    private void rosterCommand(CommandSender sender, String[] args, boolean add) {
        if (!checkAdmin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(plugin.prefixed(add ? "add-usage" : "remove-usage"));
            return;
        }
        OfflinePlayer target = findPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.prefixed("player-not-found", Placeholder.unparsed("player", args[1])));
            return;
        }
        String name = target.getName() == null ? args[1] : target.getName();
        FateStore store = plugin.store();
        if (!store.isEnabled() && !plugin.isCountingDown()) {
            sender.sendMessage(plugin.prefixed("roster-no-run"));
            return;
        }
        Player online = target.getPlayer();
        if (add) {
            store.setOptedOut(target.getUniqueId(), false);
            if (!store.addToRoster(target.getUniqueId())) {
                sender.sendMessage(plugin.prefixed("roster-already-in", Placeholder.unparsed("player", name)));
                return;
            }
            if (online != null) online.getScheduler().run(plugin, t -> plugin.releaseSpectator(online), null);
            Bukkit.getServer().sendMessage(plugin.prefixed("roster-added", Placeholder.unparsed("player", name)));
        } else {
            if (!store.removeFromRoster(target.getUniqueId())) {
                sender.sendMessage(plugin.prefixed("roster-not-in", Placeholder.unparsed("player", name)));
                return;
            }
            Bukkit.getServer().sendMessage(plugin.prefixed("roster-removed", Placeholder.unparsed("player", name)));
        }
    }

    /** /coords: post where you are in chat for everyone. */
    private void coords(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefixed("players-only"));
            return;
        }
        if (!plugin.store().get(Toggle.COORDS_CMD)) {
            player.sendMessage(plugin.prefixed("coords-off"));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastCoords.get(player.getUniqueId());
        if (last != null && now - last < 10_000L) {
            player.sendMessage(plugin.prefixed("on-cooldown",
                    Placeholder.unparsed("seconds", String.valueOf((10_000L - (now - last) + 999) / 1000))));
            return;
        }
        lastCoords.put(player.getUniqueId(), now);
        // Commands already run on the player's own thread, so their location is safe to read.
        org.bukkit.Location loc = player.getLocation();
        String dim = switch (loc.getWorld().getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
        Bukkit.getServer().sendMessage(plugin.prefixed("coords",
                Placeholder.component("player", player.name()),
                Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(loc.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())),
                Placeholder.unparsed("dimension", dim)));
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("lifeline.admin")) return true;
        sender.sendMessage(plugin.prefixed("no-permission"));
        return false;
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) return cached;
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("timer") || cmd.equals("stats") || cmd.equals("coords")) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("start") && sender.hasPermission("lifeline.admin")) {
            options.add("now");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset") && sender.hasPermission("lifeline.admin")) {
            options.add("confirm");
        } else if (args.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (sender.hasPermission("lifeline.admin")) options.addAll(ADMIN_SUBS);
        } else if (args.length == 2 && sender.hasPermission("lifeline.admin") && args[0].equalsIgnoreCase("sharing")) {
            options.addAll(List.of("on", "off", "10", "25", "50"));
        } else if (args.length == 2 && sender.hasPermission("lifeline.admin") && args[0].equalsIgnoreCase("compass")) {
            options.addAll(List.of("on", "off"));
        } else if (args.length == 2 && sender.hasPermission("lifeline.admin")
                && (args[0].equalsIgnoreCase("optout") || args[0].equalsIgnoreCase("optin")
                    || args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
