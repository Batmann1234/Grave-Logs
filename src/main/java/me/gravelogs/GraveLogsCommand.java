package me.gravelogs;

import me.gravelogs.Database.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GraveLogsCommand implements CommandExecutor, TabCompleter {

    private static final Pattern TIME = Pattern.compile("(\\d+)([wdhms])");
    private static final UUID CONSOLE = new UUID(0, 0);

    private final GraveLogs plugin;
    private final Database database;
    private final GraveListener listener;
    private final Map<UUID, List<Entry>> lastResults = new HashMap<>();

    public GraveLogsCommand(GraveLogs plugin, Database database, GraveListener listener) {
        this.plugin = plugin;
        this.database = database;
        this.listener = listener;
    }

    private Messages msg() {
        return plugin.getMessages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gravelogs.use")) {
            sender.sendMessage(msg().get("no-permission"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }

        // Atajo: /gl r:20 t:2h include:*_sword  (sin escribir "lookup")
        if (args[0].contains(":")) {
            lookup(sender, args, 0);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lookup", "l" -> lookup(sender, args, 1);
            case "page", "p" -> page(sender, args);
            case "status" -> status(sender);
            case "tp", "teleport" -> teleport(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("gravelogs.reload")) {
                    sender.sendMessage(msg().get("no-permission"));
                    return true;
                }
                plugin.reloadConfig();
                msg().reload();
                sender.sendMessage(msg().get("reloaded"));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        for (String line : msg().getList("help", "label", label)) {
            sender.sendMessage(line);
        }
        for (String line : msg().getList("help-filters", "label", label)) {
            sender.sendMessage(line);
        }
        for (String line : msg().getList("help-shortcut", "label", label)) {
            sender.sendMessage(line);
        }
    }

    private void lookup(CommandSender sender, String[] args, int from) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg().get("only-player"));
            return;
        }

        int radius = 10;
        long seconds = 86400;
        String user = null;
        String action = null;
        int max = plugin.getConfig().getInt("max-radius", 100);
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();

        for (int i = from; i < args.length; i++) {
            String[] kv = args[i].split(":", 2);
            if (kv.length < 2 || kv[1].isEmpty()) {
                sender.sendMessage(msg().get("invalid-parameter", "param", args[i]));
                return;
            }
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "r", "radio" -> {
                    try {
                        radius = Integer.parseInt(kv[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(msg().get("invalid-radius", "value", kv[1]));
                        return;
                    }
                    if (radius < 1 || radius > max) {
                        sender.sendMessage(msg().get("radius-range", "max", String.valueOf(max)));
                        return;
                    }
                }
                case "t", "tiempo" -> {
                    seconds = parseTime(kv[1]);
                    if (seconds <= 0) {
                        sender.sendMessage(msg().get("invalid-time", "value", kv[1]));
                        return;
                    }
                }
                case "u", "usuario" -> user = kv[1];
                case "i", "include" -> include.addAll(parseList(kv[1]));
                case "e", "exclude" -> exclude.addAll(parseList(kv[1]));
                case "a", "accion" -> {
                    action = normalizeAction(kv[1]);
                    if (action == null) {
                        sender.sendMessage(msg().get("invalid-action"));
                        return;
                    }
                }
                default -> {
                    sender.sendMessage(msg().get("unknown-parameter", "param", kv[0]));
                    return;
                }
            }
        }

        Location loc = player.getLocation();
        long since = System.currentTimeMillis() - seconds * 1000L;
        sender.sendMessage(msg().get("searching"));

        database.query(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                radius, since, user, action, include, exclude, results -> {
                    lastResults.put(player.getUniqueId(), results);
                    if (results.isEmpty()) {
                        player.sendMessage(msg().get("no-results"));
                        return;
                    }
                    showPage(player, results, 1);
                },
                t -> player.sendMessage(msg().get("query-error", "error", String.valueOf(t))));
    }

    private void page(CommandSender sender, String[] args) {
        UUID key = sender instanceof Player p ? p.getUniqueId() : CONSOLE;
        List<Entry> results = lastResults.get(key);
        if (results == null || results.isEmpty()) {
            sender.sendMessage(msg().get("no-search"));
            return;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(msg().get("invalid-page"));
                return;
            }
        }
        showPage(sender, results, page);
    }

    private int perPage() {
        return Math.max(1, plugin.getConfig().getInt("results-per-page", 4));
    }

    private void showPage(CommandSender sender, List<Entry> results, int page) {
        int perPage = perPage();
        int pages = (int) Math.ceil(results.size() / (double) perPage);
        if (page < 1 || page > pages) {
            sender.sendMessage(msg().get("page-range", "pages", String.valueOf(pages)));
            return;
        }
        sender.sendMessage(msg().get("page-header",
                "page", String.valueOf(page),
                "pages", String.valueOf(pages),
                "total", String.valueOf(results.size())));

        boolean canTp = sender instanceof Player && sender.hasPermission("gravelogs.teleport");
        int from = (page - 1) * perPage;
        int to = Math.min(from + perPage, results.size());
        for (int i = from; i < to; i++) {
            Entry e = results.get(i);
            sender.sendMessage(mainLine(e));
            sender.sendMessage(locationLine(e, canTp));
        }
        if (pages > 1) {
            sender.sendMessage(footer(page, pages));
        }
    }

    /** Marca temporal que se sustituye por el nombre del ítem (con hover). */
    private static final String ITEM_TOKEN = "\uE000";

    /** Línea 1: hace cuánto, quién y qué hizo. */
    private Component mainLine(Entry e) {
        String actionText = msg().get("actions." + e.action(),
                "amount", String.valueOf(e.amount()),
                "item", ITEM_TOKEN);
        Component line = msg().component("result-main",
                "ago", ago(e.time()),
                "symbol", msg().get("symbols." + e.action()),
                "player", e.player(),
                "action", actionText);
        if (e.item() != null) {
            line = line.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(ITEM_TOKEN)
                    .replacement(itemComponent(e))
                    .build());
        }
        return line;
    }

    /** Nombre del ítem en minúsculas; al pasar el cursor muestra el ítem completo (nombre, lore, encantamientos). */
    private Component itemComponent(Entry e) {
        Component text = Component.text(materialName(e.item()));
        if (e.itemData() != null) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(e.itemData()));
                stack.setAmount(Math.max(1, Math.min(e.amount(), stack.getMaxStackSize())));
                text = text.hoverEvent(stack);
            } catch (Throwable ignored) {
                // Si no se puede leer el ítem guardado, se muestra solo el nombre.
            }
        }
        return text;
    }

    /** "TOTEM_OF_UNDYING (Nombre) [2 encantamientos]" (registros viejos) -> "totem_of_undying" */
    private static String materialName(String raw) {
        int space = raw.indexOf(' ');
        return (space > 0 ? raw.substring(0, space) : raw).toLowerCase(Locale.ROOT);
    }

    /** Línea 2: coordenadas (con clic para teletransportarse). */
    private Component locationLine(Entry e, boolean canTp) {
        String[] vars = {
                "x", String.valueOf(e.x()), "y", String.valueOf(e.y()), "z", String.valueOf(e.z()),
                "world", e.world(), "owner", e.owner()};
        Component line = msg().component("result-location", vars);
        if (canTp) {
            line = line
                    .clickEvent(ClickEvent.runCommand("/gravelogs tp " + e.world() + " "
                            + e.x() + " " + e.y() + " " + e.z()))
                    .hoverEvent(HoverEvent.showText(msg().component("teleport-hover", vars)));
        }
        return line;
    }

    /** Pie de página clicable: « 1 / 2 / 3 ». */
    private Component footer(int page, int pages) {
        List<Integer> shown = new ArrayList<>();
        if (pages <= 9) {
            for (int n = 1; n <= pages; n++) shown.add(n);
        } else {
            shown.add(1);
            int start = Math.max(2, page - 2);
            int end = Math.min(pages - 1, page + 2);
            if (start > 2) shown.add(-1);
            for (int n = start; n <= end; n++) shown.add(n);
            if (end < pages - 1) shown.add(-1);
            shown.add(pages);
        }

        Component out = msg().component("page-footer-prefix")
                .append(pageButton("page-prev", page - 1, page > 1))
                .append(Component.text(" "));
        for (int idx = 0; idx < shown.size(); idx++) {
            int n = shown.get(idx);
            if (idx > 0) out = out.append(msg().component("page-separator"));
            if (n == -1) {
                out = out.append(msg().component("page-ellipsis"));
            } else if (n == page) {
                out = out.append(msg().component("page-current", "n", String.valueOf(n)));
            } else {
                out = out.append(pageButton("page-number", n, true));
            }
        }
        return out.append(Component.text(" ")).append(pageButton("page-next", page + 1, page < pages));
    }

    private Component pageButton(String key, int target, boolean clickable) {
        if (!clickable) return msg().component(key + "-disabled");
        String n = String.valueOf(target);
        return msg().component(key, "n", n)
                .clickEvent(ClickEvent.runCommand("/gravelogs page " + n))
                .hoverEvent(HoverEvent.showText(msg().component("page-hover", "n", n)));
    }

    /** /gravelogs tp <mundo> <x> <y> <z> (lo ejecuta el clic en las coordenadas). */
    private void teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg().get("only-player"));
            return;
        }
        if (!sender.hasPermission("gravelogs.teleport")) {
            sender.sendMessage(msg().get("no-permission"));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(msg().get("tp-usage"));
            return;
        }
        try {
            int x = Integer.parseInt(args[args.length - 3]);
            int y = Integer.parseInt(args[args.length - 2]);
            int z = Integer.parseInt(args[args.length - 1]);
            String worldName = String.join(" ", Arrays.asList(args).subList(1, args.length - 3));
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(msg().get("tp-world-missing", "world", worldName));
                return;
            }
            Location current = player.getLocation();
            player.teleport(new Location(world, x + 0.5, y, z + 0.5, current.getYaw(), current.getPitch()));
            sender.sendMessage(msg().get("tp-done", "x", String.valueOf(x), "y", String.valueOf(y),
                    "z", String.valueOf(z), "world", worldName));
        } catch (NumberFormatException e) {
            sender.sendMessage(msg().get("tp-usage"));
        }
    }

    private void status(CommandSender sender) {
        sender.sendMessage(msg().get("status-header"));
        sender.sendMessage(msg().get("status-open",
                "hooked", yesNo(listener.isOpenHooked()),
                "count", String.valueOf(listener.getOpenEvents())));
        sender.sendMessage(msg().get("status-interact",
                "hooked", yesNo(listener.isInteractHooked()),
                "count", String.valueOf(listener.getInteractEvents())));
        sender.sendMessage(msg().get("status-problem", "problem", listener.getLastProblem()));
        database.count(
                total -> sender.sendMessage(msg().get("status-records", "total", String.valueOf(total))),
                t -> sender.sendMessage(msg().get("status-db-error", "error", String.valueOf(t))));
    }

    private String yesNo(boolean value) {
        return msg().get(value ? "yes" : "no");
    }

    // ---------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------

    private static List<String> parseList(String input) {
        List<String> out = new ArrayList<>();
        for (String part : input.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static long parseTime(String input) {
        Matcher m = TIME.matcher(input.toLowerCase(Locale.ROOT));
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "w" -> n * 604800;
                case "d" -> n * 86400;
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
        }
        return end == input.length() ? total : -1;
    }

    private static String normalizeAction(String input) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return switch (s) {
            case "saco", "sacar", "take" -> "SACO";
            case "abrio", "abrir", "open" -> "ABRIO";
            case "recogio", "recoger", "collect" -> "RECOGIO";
            default -> null;
        };
    }

    /** Tiempo estilo CoreProtect: 0.10/m, 34.44/m, 2.50/h, 1.20/d */
    private String ago(long time) {
        double minutes = Math.max(0, System.currentTimeMillis() - time) / 60000.0;
        String value;
        if (minutes < 60) {
            value = String.format(Locale.ROOT, "%.2f/m", minutes);
        } else if (minutes < 1440) {
            value = String.format(Locale.ROOT, "%.2f/h", minutes / 60.0);
        } else {
            value = String.format(Locale.ROOT, "%.2f/d", minutes / 1440.0);
        }
        return msg().get("time-ago", "time", value);
    }

    // ---------------------------------------------------------------
    // Autocompletado (Tab)
    // ---------------------------------------------------------------

    private static final List<String> PARAM_KEYS = List.of("r:", "t:", "u:", "a:", "include:", "exclude:");
    private static final List<String> WILDCARDS = List.of("*_sword", "*_pickaxe", "*_axe", "*_shovel", "*_hoe",
            "*_helmet", "*_chestplate", "*_leggings", "*_boots", "*netherite*", "*diamond*");
    private static final List<String> ITEMS = buildItems();

    /** Todos los ítems del juego en minúsculas (diamond_sword, netherite_ingot...). */
    private static List<String> buildItems() {
        List<String> list = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || m.isAir() || !m.isItem()) continue;
            list.add(m.name().toLowerCase(Locale.ROOT));
        }
        Collections.sort(list);
        return list;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 0) return out;
        String current = args[args.length - 1];

        boolean implicit = args[0].contains(":");
        boolean explicit = args.length >= 2
                && (args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("l"));
        boolean paramMode = explicit || implicit || (args.length == 1 && current.contains(":"));

        if (paramMode) {
            completeParam(current, args, explicit ? 1 : 0, out);
            return out;
        }
        if (args.length == 1) {
            String lower = current.toLowerCase(Locale.ROOT);
            for (String s : List.of("lookup", "page", "status", "reload", "help")) {
                if (s.startsWith(lower)) out.add(s);
            }
            for (String s : PARAM_KEYS) {
                if (s.startsWith(lower)) out.add(s);
            }
        }
        return out;
    }

    private void completeParam(String current, String[] args, int from, List<String> out) {
        int colon = current.indexOf(':');

        // Todavía está escribiendo la clave (r:, t:, u:, a:, include:, exclude:)
        if (colon < 0) {
            Set<String> used = new HashSet<>();
            for (int i = from; i < args.length - 1; i++) {
                int c = args[i].indexOf(':');
                if (c > 0) used.add(canon(args[i].substring(0, c)));
            }
            String lower = current.toLowerCase(Locale.ROOT);
            for (String key : PARAM_KEYS) {
                if (key.startsWith(lower) && !used.contains(canon(key.substring(0, key.length() - 1)))) {
                    out.add(key);
                }
            }
            return;
        }

        String keyText = current.substring(0, colon + 1);
        String value = current.substring(colon + 1);

        switch (canon(current.substring(0, colon))) {
            case "r" -> {
                if (value.isEmpty()) {
                    for (int n = 1; n <= 9; n++) out.add(keyText + n);
                } else if (value.matches("\\d{1,2}")) {
                    for (int n = 0; n <= 9; n++) out.add(keyText + value + n);
                }
            }
            case "t" -> {
                if (value.isEmpty()) {
                    for (int n = 1; n <= 9; n++) out.add(keyText + n);
                } else if (value.matches("\\d+")) {
                    for (String unit : List.of("s", "m", "h", "d", "w")) out.add(keyText + value + unit);
                } else if (value.matches("(\\d+[smhdw])+")) {
                    for (int n = 1; n <= 9; n++) out.add(keyText + value + n);
                }
            }
            case "u" -> {
                String lower = value.toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(lower)) out.add(keyText + p.getName());
                }
            }
            case "a" -> {
                String lower = value.toLowerCase(Locale.ROOT);
                for (String a : List.of("saco", "abrio", "recogio")) {
                    if (a.startsWith(lower)) out.add(keyText + a);
                }
            }
            case "include", "exclude" -> completeItems(keyText, value, out);
            default -> {
            }
        }
    }

    /** Completa el último ítem de una lista separada por comas (include:diamond_sword,net...). */
    private void completeItems(String keyText, String value, List<String> out) {
        int comma = value.lastIndexOf(',');
        String head = comma >= 0 ? value.substring(0, comma + 1) : "";
        String frag = value.substring(comma + 1).toLowerCase(Locale.ROOT);
        if (frag.startsWith("minecraft:")) frag = frag.substring("minecraft:".length());

        for (String wildcard : WILDCARDS) {
            if (wildcard.startsWith(frag)) out.add(keyText + head + wildcard);
        }
        for (String item : ITEMS) {
            if (frag.isEmpty() || item.startsWith(frag) || item.contains("_" + frag)) {
                out.add(keyText + head + item);
            }
        }
    }

    /** Nombre canónico de cada parámetro (acepta alias). */
    private static String canon(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "r", "radio" -> "r";
            case "t", "tiempo" -> "t";
            case "u", "usuario" -> "u";
            case "a", "accion" -> "a";
            case "i", "include" -> "include";
            case "e", "exclude" -> "exclude";
            default -> key.toLowerCase(Locale.ROOT);
        };
    }
}
