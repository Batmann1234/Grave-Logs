package me.gravelogs;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GraveLogs extends JavaPlugin {

    private LogWriter writer;
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.writer = new LogWriter(this);

        try {
            this.database = new Database(this);
        } catch (Exception e) {
            getLogger().severe("No se pudo iniciar la base de datos SQLite: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new GraveListener(this, writer, database), this);

        PluginCommand cmd = getCommand("gravelogs");
        if (cmd != null) {
            GraveLogsCommand handler = new GraveLogsCommand(this, database);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        getLogger().info("GraveLogs activado.");
    }

    @Override
    public void onDisable() {
        if (writer != null) writer.close();
        if (database != null) database.close();
    }
}
