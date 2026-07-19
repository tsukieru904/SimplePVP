package me.tsukieru.simplepvp.message;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class MessageManager {

    private final JavaPlugin plugin;
    private YamlConfiguration yaml;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "message.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    /** Reload message.yml from disk. */
    public void reload() {
        load();
    }

    public String prefix() {
        return color(yaml.getString("prefix", "&8[&aPvP&8] &r"));
    }

    public String raw(String path) {
        return yaml.getString(path, "&cMissing message: " + path);
    }

    public String text(String path) {
        return color(raw(path));
    }

    public String text(String path, Map<String, String> placeholders) {
        String result = text(path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public String withPrefix(String path) {
        return prefix() + text(path);
    }

    public String withPrefix(String path, Map<String, String> placeholders) {
        return prefix() + text(path, placeholders);
    }

    /** Returns a list of coloured lines from a YAML string list. */
    public List<String> list(String path) {
        return list(path, Map.of());
    }

    /** Returns a coloured string list after placeholder substitution. */
    public List<String> list(String path, Map<String, String> placeholders) {
        List<String> raw = yaml.getStringList(path);
        if (raw.isEmpty()) {
            return List.of(color("&cMissing message list: " + path));
        }
        return raw.stream()
                .map(line -> {
                    String result = line;
                    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                        result = result.replace("{" + entry.getKey() + "}", entry.getValue());
                    }
                    return color(result);
                })
                .toList();
    }

    public static String color(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }
}
