package me.gravelogs;

import org.bukkit.Bukkit;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Base de datos SQLite (incluida en Paper). Todo se ejecuta en un hilo aparte. */
public final class Database {

    public record Entry(long id, long time, String action, String player, String owner,
                        String world, int x, int y, int z, String item, int amount) {}

    private final GraveLogs plugin;
    private final Connection connection;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "GraveLogs-DB"));

    public Database(GraveLogs plugin) throws Exception {
        this.plugin = plugin;
        Class.forName("org.sqlite.JDBC");
        File file = new File(plugin.getDataFolder(), "gravelogs.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS entries ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "time INTEGER NOT NULL,"
                    + "action TEXT NOT NULL,"
                    + "player TEXT NOT NULL,"
                    + "owner TEXT NOT NULL,"
                    + "world TEXT NOT NULL,"
                    + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
                    + "item TEXT,"
                    + "amount INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_loc ON entries (world, x, z, time)");
        }
    }

    public void insert(Entry e) {
        executor.execute(() -> {
            String sql = "INSERT INTO entries (time, action, player, owner, world, x, y, z, item, amount) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, e.time());
                ps.setString(2, e.action());
                ps.setString(3, e.player());
                ps.setString(4, e.owner());
                ps.setString(5, e.world());
                ps.setInt(6, e.x());
                ps.setInt(7, e.y());
                ps.setInt(8, e.z());
                ps.setString(9, e.item());
                ps.setInt(10, e.amount());
                ps.executeUpdate();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.SEVERE, "Error guardando en la base de datos", ex);
            }
        });
    }

    /** Busca en un cubo de {@code radius} bloques. Los callbacks se ejecutan en el hilo principal. */
    public void query(String world, int cx, int cy, int cz, int radius, long since,
                      String player, String action, List<String> include, List<String> exclude,
                      Consumer<List<Entry>> callback, Consumer<Throwable> onError) {
        executor.execute(() -> {
            try {
                List<Entry> list = new ArrayList<>();
                StringBuilder sql = new StringBuilder("SELECT * FROM entries WHERE world=? "
                        + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND time>=?");
                if (player != null) sql.append(" AND LOWER(player)=LOWER(?)");
                if (action != null) sql.append(" AND action=?");
                if (!include.isEmpty()) {
                    sql.append(" AND item IS NOT NULL AND (").append(likeClause(include.size())).append(")");
                }
                if (!exclude.isEmpty()) {
                    sql.append(" AND (item IS NULL OR NOT (").append(likeClause(exclude.size())).append("))");
                }
                sql.append(" ORDER BY time DESC LIMIT 1000");

                try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                    int i = 1;
                    ps.setString(i++, world);
                    ps.setInt(i++, cx - radius);
                    ps.setInt(i++, cx + radius);
                    ps.setInt(i++, cy - radius);
                    ps.setInt(i++, cy + radius);
                    ps.setInt(i++, cz - radius);
                    ps.setInt(i++, cz + radius);
                    ps.setLong(i++, since);
                    if (player != null) ps.setString(i++, player);
                    if (action != null) ps.setString(i++, action);
                    for (String pattern : include) ps.setString(i++, toLike(pattern));
                    for (String pattern : exclude) ps.setString(i++, toLike(pattern));

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(new Entry(rs.getLong("id"), rs.getLong("time"), rs.getString("action"),
                                    rs.getString("player"), rs.getString("owner"), rs.getString("world"),
                                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                                    rs.getString("item"), rs.getInt("amount")));
                        }
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(list));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Error consultando la base de datos", t);
                Bukkit.getScheduler().runTask(plugin, () -> onError.accept(t));
            }
        });
    }

    /** Compara solo el nombre del material (lo que hay antes del primer espacio del campo item). */
    private static String likeClause(int count) {
        String one = "SUBSTR(item, 1, INSTR(item || ' ', ' ') - 1) LIKE ? ESCAPE '\\'";
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < count; n++) {
            if (n > 0) sb.append(" OR ");
            sb.append(one);
        }
        return sb.toString();
    }

    /** "minecraft:*_sword" -> "%\\_SWORD". El * es comodín; sin * se busca el nombre exacto. */
    private static String toLike(String pattern) {
        String p = pattern.trim().toUpperCase(java.util.Locale.ROOT);
        if (p.startsWith("MINECRAFT:")) p = p.substring("MINECRAFT:".length());
        p = p.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return p.replace("*", "%");
    }

    /** Cuenta todos los registros guardados. */
    public void count(Consumer<Integer> callback, Consumer<Throwable> onError) {
        executor.execute(() -> {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM entries")) {
                int total = rs.next() ? rs.getInt(1) : 0;
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(total));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Error contando registros", t);
                Bukkit.getScheduler().runTask(plugin, () -> onError.accept(t));
            }
        });
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
            connection.close();
        } catch (Exception ignored) {
        }
    }
}
