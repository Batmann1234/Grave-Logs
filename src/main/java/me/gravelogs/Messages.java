package me.gravelogs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Carga messages.yml, aplica el prefijo, los colores (&) y las variables {nombre}. */
public final class Messages {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private final GraveLogs plugin;
    private FileConfiguration config;

    public Messages(GraveLogs plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        // Valores por defecto del jar, para que no falle si falta alguna clave en tu archivo.
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
        }
    }

    /** Devuelve un mensaje. Las variables se pasan como pares: "player", "Steve", "amount", "3"... */
    public String get(String key, String... pairs) {
        String raw = config.getString(key);
        if (raw == null) raw = "&c[Falta el mensaje: " + key + "]";
        return format(raw, pairs);
    }

    /** Igual que get(), pero devuelve un Component (necesario para clics y hover). */
    public Component component(String key, String... pairs) {
        return LegacyComponentSerializer.legacySection().deserialize(get(key, pairs));
    }

    public List<String> getList(String key, String... pairs) {
        List<String> out = new ArrayList<>();
        for (String line : config.getStringList(key)) {
            out.add(format(line, pairs));
        }
        return out;
    }

    private String format(String raw, String... pairs) {
        String prefix = config.getString("prefix", "");
        // Primero colores, luego variables, para que un jugador o ítem con "&" no cambie los colores.
        String text = colorize(raw.replace("{prefix}", prefix));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return text;
    }

    private static String colorize(String s) {
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) {
                rep.append('§').append(c);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return LEGACY.matcher(sb.toString()).replaceAll("§$1");
    }
}
