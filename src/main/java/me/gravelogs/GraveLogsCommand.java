package me.gravelogs;

import me.gravelogs.Database.Entry;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GraveLogsCommand implements CommandExecutor, TabCompleter {

    private static final int PER_PAGE = 10;
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gravelogs.use")) {
            sender.sendMessage("§cNo tienes permiso para usar este comando.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lookup", "l" -> lookup(sender, args);
            case "page", "p" -> page(sender, args);
            case "status" -> status(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§3----- GraveLogs -----");
        sender.sendMessage("§b/" + label + " lookup r:<radio> t:<tiempo> u:<jugador> a:<accion>");
        sender.sendMessage("§7  r: radio en bloques (por defecto 10)");
        sender.sendMessage("§7  t: tiempo hacia atrás, ej: 30m, 2h, 7d, 1d12h (por defecto 1d)");
        sender.sendMessage("§7  u: jugador que sacó/abrió (opcional)");
        sender.sendMessage("§7  a: saco, abrio o recogio (opcional)");
        sender.sendMessage("§b/" + label + " page <n> §7- ver otra página de resultados");
        sender.sendMessage("§b/" + label + " status §7- diagnóstico del plugin");
        sender.sendMessage("§7Ejemplo: §f/" + label + " lookup r:20 t:2h a:saco");
    }

    private void lookup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo se puede usar dentro del juego.");
            return;
        }

        int radius = 10;
        long seconds = 86400;
        String user = null;
        String action = null;
        int max = plugin.getConfig().getInt("max-radius", 100);

        for (int i = 1; i < args.length; i++) {
            String[] kv = args[i].split(":", 2);
            if (kv.length < 2 || kv[1].isEmpty()) {
                sender.sendMessage("§cParámetro inválido: §f" + args[i]);
                return;
            }
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "r", "radio" -> {
                    try {
                        radius = Integer.parseInt(kv[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cRadio inválido: §f" + kv[1]);
                        return;
                    }
                    if (radius < 1 || radius > max) {
                        sender.sendMessage("§cEl radio debe estar entre 1 y " + max + ".");
                        return;
                    }
                }
                case "t", "tiempo" -> {
                    seconds = parseTime(kv[1]);
                    if (seconds <= 0) {
                        sender.sendMessage("§cTiempo inválido: §f" + kv[1] + " §7(usa por ejemplo 30m, 2h, 7d)");
                        return;
                    }
                }
                case "u", "usuario" -> user = kv[1];
                case "a", "accion" -> {
                    action = normalizeAction(kv[1]);
                    if (action == null) {
                        sender.sendMessage("§cAcción inválida. Usa: saco, abrio o recogio.");
                        return;
                    }
                }
                default -> {
                    sender.sendMessage("§cParámetro desconocido: §f" + kv[0]);
                    return;
                }
            }
        }

        Location loc = player.getLocation();
        long since = System.currentTimeMillis() - seconds * 1000L;
        sender.sendMessage("§7Buscando...");

        database.query(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                radius, since, user, action, results -> {
                    lastResults.put(player.getUniqueId(), results);
                    if (results.isEmpty()) {
                        player.sendMessage("§cNo se encontraron registros de tumbas en esa zona.");
                        return;
                    }
                    showPage(player, results, 1);
                },
                t -> player.sendMessage("§cError al consultar la base de datos: " + t));
    }

    private void status(CommandSender sender) {
        sender.sendMessage("§3----- GraveLogs: estado -----");
        sender.sendMessage("§7Evento abrir tumba enganchado: " + yesNo(listener.isOpenHooked())
                + " §7(recibidos: §f" + listener.getOpenEvents() + "§7)");
        sender.sendMessage("§7Evento interactuar enganchado: " + yesNo(listener.isInteractHooked())
                + " §7(recibidos: §f" + listener.getInteractEvents() + "§7)");
        database.count(
                total -> sender.sendMessage("§7Registros en la base de datos: §f" + total),
                t -> sender.sendMessage("§cError leyendo la base de datos: " + t));
    }

    private static String yesNo(boolean value) {
        return value ? "§aSí" : "§cNO";
    }

    private void page(CommandSender sender, String[] args) {
        UUID key = sender instanceof Player p ? p.getUniqueId() : CONSOLE;
        List<Entry> results = lastResults.get(key);
        if (results == null || results.isEmpty()) {
            sender.sendMessage("§cPrimero haz una búsqueda con /gravelogs lookup.");
            return;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cNúmero de página inválido.");
                return;
            }
        }
        showPage(sender, results, page);
    }

    private void showPage(CommandSender sender, List<Entry> results, int page) {
        int pages = (int) Math.ceil(results.size() / (double) PER_PAGE);
        if (page < 1 || page > pages) {
            sender.sendMessage("§cLa página debe estar entre 1 y " + pages + ".");
            return;
        }
        sender.sendMessage("§3----- GraveLogs §7(página " + page + "/" + pages + " | "
                + results.size() + " resultados) §3-----");

        int from = (page - 1) * PER_PAGE;
        int to = Math.min(from + PER_PAGE, results.size());
        for (int i = from; i < to; i++) {
            sender.sendMessage(format(results.get(i)));
        }
        if (page < pages) {
            sender.sendMessage("§7Usa §f/gravelogs page " + (page + 1) + " §7para ver más.");
        }
    }

    private String format(Entry e) {
        String what = switch (e.action()) {
            case "SACO" -> "sacó §e" + e.amount() + "x " + e.item();
            case "RECOGIO" -> "recogió §e" + e.amount() + "x " + e.item();
            default -> "abrió la tumba";
        };
        return "§7" + ago(e.time()) + " §f- §b" + e.player() + " §f" + what
                + " §7(tumba de " + e.owner() + ") §8" + e.x() + "/" + e.y() + "/" + e.z();
    }

    // ---------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------

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

    private static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        return (s / 86400) + "d " + ((s % 86400) / 3600) + "h";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("lookup", "page", "status", "help")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length > 1 && args[0].equalsIgnoreCase("lookup")) {
            for (String s : List.of("r:10", "r:25", "r:50", "t:1h", "t:1d", "t:7d",
                    "u:", "a:saco", "a:abrio", "a:recogio")) {
                if (s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }
}
