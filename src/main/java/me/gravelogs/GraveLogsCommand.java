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

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lookup", "l" -> lookup(sender, args);
            case "page", "p" -> page(sender, args);
            case "status" -> status(sender);
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
    }

    private void lookup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg().get("only-player"));
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
                radius, since, user, action, results -> {
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

    private void showPage(CommandSender sender, List<Entry> results, int page) {
        int pages = (int) Math.ceil(results.size() / (double) PER_PAGE);
        if (page < 1 || page > pages) {
            sender.sendMessage(msg().get("page-range", "pages", String.valueOf(pages)));
            return;
        }
        sender.sendMessage(msg().get("page-header",
                "page", String.valueOf(page),
                "pages", String.valueOf(pages),
                "total", String.valueOf(results.size())));

        int from = (page - 1) * PER_PAGE;
        int to = Math.min(from + PER_PAGE, results.size());
        for (int i = from; i < to; i++) {
            sender.sendMessage(format(results.get(i)));
        }
        if (page < pages) {
            sender.sendMessage(msg().get("page-hint", "next", String.valueOf(page + 1)));
        }
    }

    private String format(Entry e) {
        String actionText = msg().get("actions." + e.action(),
                "amount", String.valueOf(e.amount()),
                "item", String.valueOf(e.item()));
        return msg().get("result-line",
                "ago", ago(e.time()),
                "player", e.player(),
                "action", actionText,
                "owner", e.owner(),
                "x", String.valueOf(e.x()),
                "y", String.valueOf(e.y()),
                "z", String.valueOf(e.z()));
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
            for (String s : List.of("lookup", "page", "status", "reload", "help")) {
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
