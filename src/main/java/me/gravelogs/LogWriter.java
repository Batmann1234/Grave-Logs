package me.gravelogs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Escribe los logs en un archivo por día, en otro hilo para no dar lag. */
public final class LogWriter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final GraveLogs plugin;
    private final File folder;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "GraveLogs-Writer"));

    public LogWriter(GraveLogs plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "logs");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de logs.");
        }
    }

    public File getFolder() {
        return folder;
    }

    public void log(String line) {
        LocalDateTime now = LocalDateTime.now();
        String full = "[" + TIME.format(now) + "] " + line;

        if (plugin.getConfig().getBoolean("console", false)) {
            plugin.getLogger().info(line);
        }

        executor.execute(() -> {
            File file = new File(folder, DAY.format(now) + ".log");
            try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(full);
                w.newLine();
            } catch (IOException e) {
                plugin.getLogger().warning("Error escribiendo log: " + e.getMessage());
            }
        });
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
