package dev.nora.lifeline;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * /lifeline settings. Admins get the server menu, split into "Run", "Rules (everyone)" and
 * "Display" rows, with a button to their own settings. Everyone else gets "My settings", which
 * only changes what they see. Every change is confirmed in chat; rule changes are announced to
 * all players. Clicks run on the clicking player's thread and settings live in the thread-safe
 * {@link FateStore}, so this is Folia-safe.
 */
final class FateMenu implements InventoryHolder {

    enum Kind { SERVER, PERSONAL }

    private static final double[] SHARE_STEPS = {0.10, 0.25, 0.50, 0.75, 1.0};

    private final LifelinePlugin plugin;
    private final Kind kind;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Boolean>> actions = new HashMap<>();

    FateMenu(LifelinePlugin plugin, Kind kind, Player viewer) {
        this.plugin = plugin;
        this.kind = kind;
        this.viewer = viewer;
        int size = kind == Kind.SERVER ? 45 : 27;
        String title = kind == Kind.SERVER ? "Lifeline · server settings" : "Lifeline · my settings";
        this.inventory = Bukkit.createInventory(this, size, Component.text(title));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void open() {
        viewer.openInventory(inventory);
    }

    // ── Layout ───────────────────────────────────────────

    private void render() {
        actions.clear();
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        if (kind == Kind.SERVER) renderServer(); else renderPersonal();
    }

    private void renderServer() {
        FateStore s = plugin.store();
        // Row 1: the run itself
        label(0, Material.CLOCK, "Run", "Starting, stopping and", "how a run ends.");
        boolean running = s.isEnabled();
        set(1, item(Material.TOTEM_OF_UNDYING, title(running ? "Run: going" : "Run: not started", running),
                lore(running ? "Click to pause (timer stops)." : "Click to start (3-2-1 countdown).",
                        "Roster: " + s.roster().size() + " player(s)"), running), right -> {
            if (s.isEnabled()) {
                plugin.stopRun();
            } else {
                viewer.closeInventory();
                plugin.startRun(true);
            }
        });
        Goal goal = s.goal();
        set(2, item(Material.DRAGON_HEAD, title("Win: " + goal.label, goal != Goal.NONE),
                lore("What ends a run as a win.", "Click to change."), goal != Goal.NONE), right -> {
            s.setGoal(goal.next());
            announceRule("Win goal", s.goal().label);
        });
        LateJoin late = s.lateJoin();
        set(3, item(Material.OAK_DOOR, title("Late joiners: " + (late == LateJoin.SPECTATE ? "spectate" : "blocked"), true),
                lore(late.label + ".", "Admins can add people with", "/lifeline add <player>. Click to change."), false), right -> {
            s.setLateJoin(late.next());
            announceRule("Late joiners", s.lateJoin().label.toLowerCase());
        });
        toggle(4, Material.GRASS_BLOCK, Toggle.WORLD_RESET);
        toggle(5, Material.REPEATER, Toggle.AUTO_CONTINUE);

        // Row 2: rules that change gameplay for everyone
        label(9, Material.PAPER, "Rules (everyone)", "Changes are announced in chat", "and listed in /lifeline rules.");
        int pct = (int) Math.round(s.sharingFraction() * 100);
        set(10, item(Material.REDSTONE, title("Damage sharing", s.isSharing()),
                lore("Everyone takes " + pct + "% of each hit.", "Right-click: change the share.", state(s.isSharing())), s.isSharing()), right -> {
            if (right) s.setSharing(s.isSharing(), nextShare(s.sharingFraction()));
            else s.setSharing(!s.isSharing(), s.sharingFraction());
            announceRule("Damage sharing", s.isSharing() ? "on at " + Math.round(s.sharingFraction() * 100) + "%" : "off");
        });
        toggle(11, Material.IRON_SWORD, Toggle.COMBAT_LOG);
        toggle(12, Material.SNOWBALL, Toggle.AFK_KICK);
        toggle(13, Material.ZOMBIE_HEAD, Toggle.AFK_PROTECT);
        toggle(14, Material.ENDER_PEARL, Toggle.TPA);
        toggle(15, Material.FILLED_MAP, Toggle.COORDS_CMD);

        // Row 3: display, server-wide
        label(18, Material.SPYGLASS, "Display", "What everyone sees.", "Players can hide some in My settings.");
        toggle(19, Material.COMPASS, Toggle.COMPASS);
        toggle(20, Material.RECOVERY_COMPASS, Toggle.COMPASS_ITEM);
        toggle(21, Material.NOTE_BLOCK, Toggle.ALERT_SOUND);
        toggle(22, Material.LIGHTNING_ROD, Toggle.LIGHTNING);
        toggle(23, Material.WRITABLE_BOOK, Toggle.STATS_SUMMARY);
        toggle(24, Material.NAME_TAG, Toggle.TAB_INFO);
        toggle(25, Material.GLISTERING_MELON_SLICE, Toggle.NAMETAG);
        toggle(26, Material.CLOCK, Toggle.AFK_FLAG);

        set(39, item(Material.PLAYER_HEAD, title("My settings", true), lore("Things only you see."), false),
                right -> new FateMenu(plugin, Kind.PERSONAL, viewer).open());
        set(41, item(Material.BARRIER, Component.text("Close", NamedTextColor.GRAY), List.of(), false),
                right -> viewer.closeInventory());
    }

    private void renderPersonal() {
        FateStore s = plugin.store();
        label(4, Material.PLAYER_HEAD, "My settings", "Only changes what you see.", "Server rules: /lifeline rules");
        int[] slots = {10, 12, 14, 16};
        Material[] icons = {Material.REDSTONE_TORCH, Material.COMPASS, Material.NOTE_BLOCK, Material.NAME_TAG};
        Pref[] prefs = Pref.values();
        for (int i = 0; i < prefs.length && i < slots.length; i++) {
            Pref pref = prefs[i];
            boolean on = s.pref(viewer.getUniqueId(), pref);
            set(slots[i], item(icons[i], title(pref.label, on), lore(pref.description, state(on)), on), right -> {
                boolean now = !s.pref(viewer.getUniqueId(), pref);
                s.setPref(viewer.getUniqueId(), pref, now);
                viewer.sendMessage(plugin.prefixed("pref-changed",
                        Placeholder.unparsed("setting", pref.label),
                        Placeholder.component("state", stateWord(now))));
            });
        }
        if (viewer.hasPermission("lifeline.admin")) {
            set(21, item(Material.COMPARATOR, title("Server settings", true), lore("Back to the server menu."), false),
                    right -> new FateMenu(plugin, Kind.SERVER, viewer).open());
        }
        set(23, item(Material.BARRIER, Component.text("Close", NamedTextColor.GRAY), List.of(), false),
                right -> viewer.closeInventory());
    }

    // ── Helpers ──────────────────────────────────────────

    private void toggle(int slot, Material icon, Toggle t) {
        FateStore s = plugin.store();
        boolean on = s.get(t);
        set(slot, item(icon, title(t.label, on), lore(t.line1, t.line2, state(on)), on), right -> {
            boolean now = !s.get(t);
            s.set(t, now);
            if (t == Toggle.NAMETAG) plugin.display().applyNametags();
            if (t.rule) announceRule(t.label, now ? "on" : "off");
            else viewer.sendMessage(plugin.prefixed("setting-changed",
                    Placeholder.unparsed("setting", t.label),
                    Placeholder.component("state", stateWord(now))));
            if (t.rule) {
                String sentence = now ? t.whenOn : t.whenOff;
                if (sentence != null) Bukkit.getServer().sendMessage(plugin.msg("rule-explain", Placeholder.unparsed("text", sentence)));
            }
        });
    }

    /** Everyone hears about rule changes, so nobody wonders whether leaving will kill them. */
    private void announceRule(String rule, String value) {
        Bukkit.getServer().sendMessage(plugin.prefixed("rule-changed",
                Placeholder.unparsed("admin", viewer.getName()),
                Placeholder.unparsed("rule", rule),
                Placeholder.unparsed("value", value)));
    }

    private void set(int slot, ItemStack stack, Consumer<Boolean> onClick) {
        inventory.setItem(slot, stack);
        actions.put(slot, onClick);
    }

    private void label(int slot, Material icon, String name, String line1, String line2) {
        inventory.setItem(slot, item(icon, Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true),
                List.of(Component.text(line1, NamedTextColor.GRAY), Component.text(line2, NamedTextColor.GRAY)), false));
    }

    private static Component title(String name, boolean on) {
        return Component.text(name, on ? NamedTextColor.WHITE : NamedTextColor.GRAY);
    }

    private static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) {
            if (l == null) continue;
            if (l.equals("\u0000on")) out.add(Component.text("On", NamedTextColor.GREEN));
            else if (l.equals("\u0000off")) out.add(Component.text("Off", NamedTextColor.DARK_GRAY));
            else out.add(Component.text(l, NamedTextColor.GRAY));
        }
        return out;
    }

    private static String state(boolean on) {
        return on ? "\u0000on" : "\u0000off";
    }

    private static Component stateWord(boolean on) {
        return on ? Component.text("on", NamedTextColor.GREEN) : Component.text("off", NamedTextColor.GRAY);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> clean = new ArrayList<>();
            for (Component c : lore) clean.add(c.decoration(TextDecoration.ITALIC, false));
            meta.lore(clean);
            meta.setEnchantmentGlintOverride(glint);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static double nextShare(double current) {
        for (double step : SHARE_STEPS) {
            if (step > current + 0.001) return step;
        }
        return SHARE_STEPS[0];
    }

    private void click(Player player, int slot, boolean rightClick) {
        if (player != viewer) return;
        if (kind == Kind.SERVER && !player.hasPermission("lifeline.admin")) {
            player.closeInventory();
            return;
        }
        Consumer<Boolean> action = actions.get(slot);
        if (action == null) return;
        action.accept(rightClick);
        player.playSound(Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.5f, 1.2f));
        if (player.getOpenInventory().getTopInventory().getHolder() == this) render();
    }

    /** Routes clicks to whichever FateMenu the player has open and stops items being taken. */
    static final class Clicks implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof FateMenu menu)) return;
            event.setCancelled(true);
            HumanEntity who = event.getWhoClicked();
            if (who instanceof Player player && event.getRawSlot() < menu.inventory.getSize()) {
                menu.click(player, event.getRawSlot(), event.isRightClick());
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder() instanceof FateMenu) event.setCancelled(true);
        }
    }
}
