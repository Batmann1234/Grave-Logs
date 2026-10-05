package me.gravelogs;

import me.gravelogs.Database.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.logging.Level;

public final class GraveListener implements Listener {

    /** Datos básicos de la tumba. */
    private record GraveInfo(String owner, String world, int x, int y, int z) {
        String location() {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    /**
     * Estado COMPARTIDO de un menú de tumba. Todos los jugadores que tienen la misma tumba abierta
     * usan el mismo objeto, así un ítem sacado se registra una sola vez y a nombre de quien lo sacó.
     */
    private static final class Tracked {
        final Inventory inventory;
        final GraveInfo info;
        Map<ItemStack, Integer> snapshot;  // contenido tras el último registro
        Player pendingActor;               // quién hizo el último clic que aún no se ha registrado
        int viewers;

        Tracked(Inventory inventory, GraveInfo info, Map<ItemStack, Integer> snapshot) {
            this.inventory = inventory;
            this.info = info;
            this.snapshot = snapshot;
        }
    }

    private final GraveLogs plugin;
    private final LogWriter writer;
    private final Database database;
    private final Map<UUID, Tracked> sessions = new HashMap<>();
    private final Map<Inventory, Tracked> byInventory = new IdentityHashMap<>();

    private volatile String lastProblem = "ninguno";
    public String getLastProblem() { return lastProblem; }

    private volatile boolean openHooked;
    private volatile boolean interactHooked;
    private final AtomicInteger openEvents = new AtomicInteger();
    private final AtomicInteger interactEvents = new AtomicInteger();

    public boolean isOpenHooked() { return openHooked; }
    public boolean isInteractHooked() { return interactHooked; }
    public int getOpenEvents() { return openEvents.get(); }
    public int getInteractEvents() { return interactEvents.get(); }

    public GraveListener(GraveLogs plugin, LogWriter writer, Database database) {
        this.plugin = plugin;
        this.writer = writer;
        this.database = database;
    }

    // ---------------------------------------------------------------
    // Eventos de AxGraves
    // ---------------------------------------------------------------

    /** El jugador abre el menú de la tumba. */
    private void handleOpen(Event event) {
        if (openEvents.incrementAndGet() == 1) {
            dump("GraveOpenEvent", event);
            dump("Grave", call(event, "getGrave"));
        }
        Player player = player(event);
        if (player == null) {
            lastProblem = "GraveOpenEvent: no se pudo leer el jugador";
            plugin.getLogger().warning("GraveOpenEvent recibido pero no se pudo leer el jugador.");
            return;
        }
        GraveInfo info = info(player, call(event, "getGrave"));
        record("ABRIO", player, info, null, 0);

        // Esperamos 1 tick a que el menú esté realmente abierto.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getType() == InventoryType.CRAFTING || top.getSize() == 0) return;
            Tracked t = byInventory.get(top);
            if (t == null) {
                t = new Tracked(top, info, count(top));
                byInventory.put(top, t);
            }
            Tracked previous = sessions.put(player.getUniqueId(), t);
            if (previous != t) {
                t.viewers++;
                if (previous != null) release(previous);
            }
        }, 1L);
    }

    /** El jugador interactúa con la tumba (abrir/recoger). Detectamos "recoger todo". */
    private void handleInteract(Event event) {
        interactEvents.incrementAndGet();
        if (!plugin.getConfig().getBoolean("log-collect", true)) return;

        Player player = player(event);
        if (player == null) return;
        GraveInfo info = info(player, call(event, "getGrave"));
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

    /** Se engancha a los eventos de AxGraves sin necesidad de compilar contra su API. */
    public void registerAxGraves() {
        Plugin ax = Bukkit.getPluginManager().getPlugin("AxGraves");
        if (ax == null) {
            plugin.getLogger().severe("AxGraves no está instalado: GraveLogs no registrará nada.");
            return;
        }
        openHooked = hook(ax, "GraveOpenEvent", this::handleOpen);
        interactHooked = hook(ax, "GraveInteractEvent", this::handleInteract);
    }

    @SuppressWarnings("unchecked")
    private boolean hook(Plugin ax, String simpleName, Consumer<Event> handler) {
        Class<?> clazz = findClass(ax, simpleName);
        if (clazz == null || !Event.class.isAssignableFrom(clazz)) {
            plugin.getLogger().warning("No se encontró el evento " + simpleName + " en AxGraves.");
            return false;
        }
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) clazz, this, EventPriority.MONITOR,
                (listener, event) -> {
                    try {
                        if (clazz.isInstance(event)) handler.accept(event);
                    } catch (Throwable t) {
                        lastProblem = simpleName + ": " + t;
                        plugin.getLogger().log(Level.SEVERE, "Error procesando " + simpleName, t);
                    }
                }, plugin, true);
        plugin.getLogger().info("Enganchado a " + clazz.getName());
        return true;
    }

    /** Busca la clase dentro del jar de AxGraves, sea cual sea su paquete en esa versión. */
    private Class<?> findClass(Plugin ax, String simpleName) {
        try {
            File file = new File(ax.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            try (JarFile jar = new JarFile(file)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.toLowerCase().contains("axgraves") && name.endsWith("/" + simpleName + ".class")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        return Class.forName(className, false, ax.getClass().getClassLoader());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error buscando " + simpleName + ": " + e.getMessage());
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Cambios dentro del menú de la tumba
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        handleChange(event.getWhoClicked(), event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        handleChange(event.getWhoClicked(), event.getView().getTopInventory());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Tracked t = sessions.get(player.getUniqueId());
        if (t == null || t.inventory != event.getInventory()) return;
        finalizeChanges(t);
        sessions.remove(player.getUniqueId());
        release(t);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Tracked t = sessions.remove(event.getPlayer().getUniqueId());
        if (t != null) {
            finalizeChanges(t);
            release(t);
        }
    }

    private void handleChange(HumanEntity who, Inventory top) {
        if (!(who instanceof Player player)) return;
        Tracked t = sessions.get(player.getUniqueId());
        if (t == null || t.inventory != top) return;

        // Cuando llega un clic, todos los anteriores (de este u otro jugador) ya se aplicaron:
        // se registran ahora, cada uno a nombre de quien lo hizo.
        finalizeChanges(t);
        t.pendingActor = player;
        // El efecto de ESTE clic se aplica después del evento, así que se registra en el siguiente tick.
        Bukkit.getScheduler().runTask(plugin, () -> finalizeChanges(t));
    }

    /** Registra lo que falta respecto al último estado, a nombre del jugador que hizo el clic pendiente. */
    private void finalizeChanges(Tracked t) {
        Player actor = t.pendingActor;
        if (actor == null) return;

        Map<ItemStack, Integer> now = count(t.inventory);
        Map<ItemStack, Integer> taken = diff(t.snapshot, now);
        for (Map.Entry<ItemStack, Integer> e : taken.entrySet()) {
            record("SACO", actor, t.info, e.getKey(), e.getValue());
        }
        if (plugin.getConfig().getBoolean("log-deposit", true)) {
            Map<ItemStack, Integer> added = diff(now, t.snapshot);
            for (Map.Entry<ItemStack, Integer> e : added.entrySet()) {
                record("PUSO", actor, t.info, e.getKey(), e.getValue());
            }
        }
        t.snapshot = now;
        t.pendingActor = null;
    }

    /** Un jugador menos mirando este menú; si era el último, se libera el estado compartido. */
    private void release(Tracked t) {
        t.viewers--;
        if (t.viewers <= 0) {
            byInventory.remove(t.inventory);
        }
    }

    // ---------------------------------------------------------------
    // Registro
    // ---------------------------------------------------------------

    private void record(String action, Player player, GraveInfo info, ItemStack item, int amount) {
        // Solo se guarda el nombre del material (en minúsculas); el ítem completo va aparte para el hover.
        String itemName = item == null ? null : item.getType().name().toLowerCase(Locale.ROOT);
        String itemData = null;
        if (item != null) {
            try {
                itemData = Base64.getEncoder().encodeToString(item.serializeAsBytes());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "No se pudo serializar el ítem " + itemName, t);
            }
        }

        database.insert(new Entry(0, System.currentTimeMillis(), action, player.getName(), info.owner(),
                info.world(), info.x(), info.y(), info.z(), itemName, amount, itemData));

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

    // ---------------------------------------------------------------
    // Lectura de datos de AxGraves por reflexión
    // ---------------------------------------------------------------

    /** Llama al primer método sin argumentos que exista con alguno de esos nombres. */
    private static Object call(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    break;
                }
            }
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Player player(Event event) {
        Object o = call(event, "getPlayer");
        if (o instanceof Player p) return p;
        for (Method m : event.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && Player.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(event);
                    if (r instanceof Player p) return p;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** Escribe en consola los métodos disponibles (solo la primera vez), para diagnóstico. */
    private void dump(String label, Object o) {
        if (o == null) {
            plugin.getLogger().info("[debug] " + label + ": null");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : o.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getDeclaringClass() != Object.class
                    && !m.getDeclaringClass().getName().startsWith("org.bukkit")) {
                sb.append(m.getName()).append("():").append(m.getReturnType().getSimpleName()).append(", ");
            }
        }
        plugin.getLogger().info("[debug] " + label + " (" + o.getClass().getName() + "): " + sb);
    }

    private static GraveInfo info(Player player, Object grave) {
        String owner = "desconocido";
        Object o = call(grave, "getOwner", "getPlayer", "getOwnerName", "getPlayerName",
                "getOwnerUUID", "getOwnerUuid", "getUuid", "getUUID");
        if (o instanceof OfflinePlayer op) {
            if (op.getName() != null) owner = op.getName();
        } else if (o instanceof UUID id) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            if (n != null) owner = n;
        } else if (o instanceof String str) {
            owner = str;
        }

        // Si no se puede leer la ubicación de la tumba, se usa la del jugador (está a pocos bloques).
        Object lo = call(grave, "getLocation");
        Location l = lo instanceof Location loc ? loc : player.getLocation();
        String world = l.getWorld() != null ? l.getWorld().getName() : "desconocido";
        return new GraveInfo(owner, world, l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
