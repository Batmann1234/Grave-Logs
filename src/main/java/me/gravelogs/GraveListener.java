package me.gravelogs;

import com.artillexstudios.axgraves.api.events.GraveInteractEvent;
import com.artillexstudios.axgraves.api.events.GraveOpenEvent;
import com.artillexstudios.axgraves.grave.Grave;
import me.gravelogs.Database.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class GraveListener implements Listener {

    /** Datos básicos de la tumba. */
    private record GraveInfo(String owner, String world, int x, int y, int z) {
        String location() {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    /** Menú de tumba que un jugador tiene abierto + su contenido anterior. */
    private static final class Session {
        final Inventory inventory;
        final GraveInfo info;
        Map<ItemStack, Integer> last;

        Session(Inventory inventory, GraveInfo info, Map<ItemStack, Integer> last) {
            this.inventory = inventory;
            this.info = info;
            this.last = last;
        }
    }

    private final GraveLogs plugin;
    private final LogWriter writer;
    private final Database database;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public GraveListener(GraveLogs plugin, LogWriter writer, Database database) {
        this.plugin = plugin;
        this.writer = writer;
        this.database = database;
    }

    // ---------------------------------------------------------------
    // Eventos de AxGraves
    // ---------------------------------------------------------------

    /** El jugador abre el menú de la tumba. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGraveOpen(GraveOpenEvent event) {
        Player player = event.getPlayer();
        GraveInfo info = info(event.getGrave());

        // Esperamos 1 tick a que el menú esté realmente abierto.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getType() == InventoryType.CRAFTING || top.getSize() == 0) return;
            sessions.put(player.getUniqueId(), new Session(top, info, count(top)));
            record("ABRIO", player, info, null, 0);
        }, 1L);
    }

    /** El jugador interactúa con la tumba (abrir/recoger). Detectamos "recoger todo". */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGraveInteract(GraveInteractEvent event) {
        if (!plugin.getConfig().getBoolean("log-collect", true)) return;

        Player player = event.getPlayer();
        GraveInfo info = info(event.getGrave());
        Map<ItemStack, Integer> before = count(player.getInventory());

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Si abrió el menú, ya lo registra la sesión.
            if (sessions.containsKey(player.getUniqueId())) return;

            Map<ItemStack, Integer> after = count(player.getInventory());
            Map<ItemStack, Integer> gained = diff(after, before);
            for (Map.Entry<ItemStack, Integer> e : gained.entrySet()) {
                record("RECOGIO", player, info, e.getKey(), e.getValue());
            }
        }, 2L);
    }

    // ---------------------------------------------------------------
    // Cambios dentro del menú de la tumba
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleCheck(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleCheck(player);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.inventory != event.getInventory()) return;
        check(player, session);
        sessions.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void scheduleCheck(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> check(player, session));
    }

    /** Compara el contenido actual con el anterior y registra lo que falta. */
    private void check(Player player, Session session) {
        Map<ItemStack, Integer> now = count(session.inventory);
        Map<ItemStack, Integer> taken = diff(session.last, now);

        for (Map.Entry<ItemStack, Integer> e : taken.entrySet()) {
            record("SACO", player, session.info, e.getKey(), e.getValue());
        }
        session.last = now;
    }

    // ---------------------------------------------------------------
    // Registro
    // ---------------------------------------------------------------

    private void record(String action, Player player, GraveInfo info, ItemStack item, int amount) {
        String itemName = item == null ? null : describe(item);

        database.insert(new Entry(0, System.currentTimeMillis(), action, player.getName(), info.owner(),
                info.world(), info.x(), info.y(), info.z(), itemName, amount));

        if (plugin.getConfig().getBoolean("file-logs", true) || plugin.getConfig().getBoolean("console", false)) {
            String line = action + " | jugador=" + player.getName() + " | dueño=" + info.owner()
                    + " | tumba=" + info.location()
                    + (itemName != null ? " | " + amount + "x " + itemName : "");
            writer.log(line);
        }
    }

    // ---------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------

    private static Map<ItemStack, Integer> count(Inventory inventory) {
        Map<ItemStack, Integer> map = new LinkedHashMap<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            ItemStack key = item.clone();
            key.setAmount(1);
            map.merge(key, item.getAmount(), Integer::sum);
        }
        return map;
    }

    /** Devuelve lo que hay de más en {@code a} respecto a {@code b}. */
    private static Map<ItemStack, Integer> diff(Map<ItemStack, Integer> a, Map<ItemStack, Integer> b) {
        Map<ItemStack, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<ItemStack, Integer> e : a.entrySet()) {
            int extra = e.getValue() - b.getOrDefault(e.getKey(), 0);
            if (extra > 0) result.put(e.getKey(), extra);
        }
        return result;
    }

    private static String describe(ItemStack item) {
        StringBuilder sb = new StringBuilder(item.getType().name());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                sb.append(" (").append(meta.getDisplayName().replaceAll("§.", "")).append(")");
            }
            if (meta.hasEnchants()) {
                sb.append(" [").append(meta.getEnchants().size()).append(" encantamientos]");
            }
        }
        return sb.toString();
    }

    /*
     * ⚠ Si al compilar te marca error aquí, tu versión de AxGraves llama distinto
     * a estos métodos. Es el único sitio donde se usa la clase Grave.
     */
    private static GraveInfo info(Grave grave) {
        String owner = "desconocido";
        OfflinePlayer op = grave.getOwner();
        if (op != null && op.getName() != null) owner = op.getName();

        Location l = grave.getLocation();
        String world = l != null && l.getWorld() != null ? l.getWorld().getName() : "desconocido";
        int x = l != null ? l.getBlockX() : 0;
        int y = l != null ? l.getBlockY() : 0;
        int z = l != null ? l.getBlockZ() : 0;
        return new GraveInfo(owner, world, x, y, z);
    }
}
