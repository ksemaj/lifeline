package dev.nora.lifeline;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One teleport per player per run (server rule, off by default). /tpa sends a request with
 * clickable [Accept] and [Deny]; the use is only spent when it's accepted. Folia-safe: the
 * target's location is read on the target's thread, the teleport runs on the requester's.
 */
final class Tpa implements TabExecutor, Listener {

    private static final long EXPIRE_MS = 60_000L;

    private record Request(UUID from, String fromName, UUID to, String toName, long expires) {}

    private final LifelinePlugin plugin;
    /** Keyed by the requester: everyone has at most one open request. */
    private final Map<UUID, Request> open = new ConcurrentHashMap<>();

    Tpa(LifelinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefixed("players-only"));
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> request(player, args);
            case "tpaccept" -> answer(player, args, true);
            case "tpdeny" -> answer(player, args, false);
            default -> { }
        }
        return true;
    }

    private void request(Player from, String[] args) {
        FateStore store = plugin.store();
        if (!store.get(Toggle.TPA)) {
            from.sendMessage(plugin.prefixed("tpa-off"));
            return;
        }
        if (!plugin.isOptedIn(from)) {
            from.sendMessage(plugin.prefixed("tpa-not-running"));
            return;
        }
        if (args.length < 1) {
            from.sendMessage(plugin.prefixed(store.hasUsedTpa(from.getUniqueId()) ? "tpa-used" : "tpa-usage"));
            return;
        }
        if (store.hasUsedTpa(from.getUniqueId())) {
            from.sendMessage(plugin.prefixed("tpa-used"));
            return;
        }
        Player to = Bukkit.getPlayerExact(args[0]);
        if (to == null || to.equals(from) || !plugin.isOptedIn(to)) {
            from.sendMessage(plugin.prefixed("tpa-no-target", Placeholder.unparsed("player", args[0])));
            return;
        }
        if (plugin.activity().inCombat(from.getUniqueId())) {
            from.sendMessage(plugin.prefixed("tpa-in-combat"));
            return;
        }
        Request req = new Request(from.getUniqueId(), from.getName(), to.getUniqueId(), to.getName(),
                System.currentTimeMillis() + EXPIRE_MS);
        open.put(from.getUniqueId(), req);

        Component accept = plugin.msg("tpa-accept-button")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + from.getName()))
                .hoverEvent(HoverEvent.showText(plugin.msg("tpa-accept-hover", Placeholder.unparsed("player", from.getName()))));
        Component deny = plugin.msg("tpa-deny-button")
                .clickEvent(ClickEvent.runCommand("/tpdeny " + from.getName()))
                .hoverEvent(HoverEvent.showText(plugin.msg("tpa-deny-hover")));
        to.sendMessage(plugin.prefixed("tpa-incoming", Placeholder.unparsed("player", from.getName()),
                Placeholder.component("accept", accept), Placeholder.component("deny", deny)));
        from.sendMessage(plugin.prefixed("tpa-sent", Placeholder.unparsed("player", to.getName())));

        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (open.remove(req.from(), req)) {
                Player p = Bukkit.getPlayer(req.from());
                if (p != null) p.sendMessage(plugin.prefixed("tpa-expired", Placeholder.unparsed("player", req.toName())));
            }
        }, EXPIRE_MS / 50);
    }

    private void answer(Player to, String[] args, boolean accept) {
        Request req = find(to.getUniqueId(), args.length >= 1 ? args[0] : null);
        if (req == null || req.expires() < System.currentTimeMillis()) {
            if (req != null) open.remove(req.from(), req);
            to.sendMessage(plugin.prefixed("tpa-none"));
            return;
        }
        open.remove(req.from(), req);
        Player from = Bukkit.getPlayer(req.from());
        if (from == null) {
            to.sendMessage(plugin.prefixed("tpa-none"));
            return;
        }
        if (!accept) {
            to.sendMessage(plugin.prefixed("tpa-denied-you", Placeholder.unparsed("player", req.fromName())));
            from.sendMessage(plugin.prefixed("tpa-denied", Placeholder.unparsed("player", to.getName())));
            return;
        }
        FateStore store = plugin.store();
        if (!store.get(Toggle.TPA) || !plugin.isOptedIn(from) || store.hasUsedTpa(req.from())) {
            to.sendMessage(plugin.prefixed("tpa-none"));
            return;
        }
        if (plugin.activity().inCombat(req.from())) {
            to.sendMessage(plugin.prefixed("tpa-they-in-combat", Placeholder.unparsed("player", req.fromName())));
            from.sendMessage(plugin.prefixed("tpa-in-combat"));
            return;
        }
        store.useTpa(req.from());
        String toName = to.getName();
        // Read where the target stands on their own thread, then move the requester on theirs.
        to.getScheduler().run(plugin, t -> {
            Location where = to.getLocation();
            from.getScheduler().run(plugin, t2 -> from.teleportAsync(where).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    from.sendMessage(plugin.prefixed("tpa-done", Placeholder.unparsed("player", toName)));
                    to.sendMessage(plugin.prefixed("tpa-arrived", Placeholder.unparsed("player", req.fromName())));
                }
            }), null);
        }, null);
    }

    /** The request sent to this player, by name if given, otherwise the newest one. */
    private Request find(UUID to, String fromName) {
        Request best = null;
        for (Request r : open.values()) {
            if (!r.to().equals(to)) continue;
            if (fromName != null && !r.fromName().equalsIgnoreCase(fromName)) continue;
            if (best == null || r.expires() > best.expires()) best = r;
        }
        return best;
    }

    /** New run, new teleport: drop any open requests. */
    void clear() {
        open.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        open.values().removeIf(r -> r.from().equals(id) || r.to().equals(id));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("tpa")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != sender && p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        } else if (sender instanceof Player me) {
            for (Request r : open.values()) {
                if (r.to().equals(me.getUniqueId()) && r.fromName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(r.fromName());
            }
        }
        return out;
    }
}
