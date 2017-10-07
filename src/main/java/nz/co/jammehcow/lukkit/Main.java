package nz.co.jammehcow.lukkit;

import nz.co.jammehcow.lukkit.environment.LuaEnvironment;
import nz.co.jammehcow.lukkit.environment.plugin.LukkitPlugin;
import nz.co.jammehcow.lukkit.environment.plugin.LukkitPluginLoader;
import org.apache.commons.lang.ArrayUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaError;
import org.reflections.Reflections;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Main entry class of the plugin.
 *
 * @author jammehcow
 */
public class Main extends JavaPlugin {
    // Config version
    private static final int CFG_VERSION = 3;

    /**
     * The Logger for Lukkit.
     */
    static Logger logger;
    /**
     * The instance of the plugin. Used for external access by plugin wrappers etc..
     */
    public static Main instance;
    private static long loadTime = 0;

    private enum ZipOperation {
        /**
         * Zip operation.
         */
        PACKAGE,
        /**
         * Unzip operation.
         */
        UNPACK
    }

    /**
     * The events collected at runtime to match plugin event registrations against.
     */
    public static HashMap<String, Class<? extends Event>> events = new HashMap<>();
    static {
        // TODO: It works, sure, but it's shit.
        // Get all the events in the Bukkit events package
        Reflections reflections = new Reflections("org.bukkit.event");
        // Iterate through the events and add their name + class object to the events HashMap
        reflections.getSubTypesOf(Event.class).forEach(c -> {
            if (reflections.getSubTypesOf(c).isEmpty()) events.put(c.getSimpleName(), c);
        });
    }

    // The Lukkit PluginManager
    private PluginManager pluginManager;

    @Override
    public void onEnable() {
        // Check for updates if it's enabled in the config
        if (getConfig().get("update-checker").equals(true))
            UpdateChecker.checkForUpdates(getDescription().getVersion());

        // Set up the tab completer for the /lukkit command
        this.getCommand("lukkit").setTabCompleter(new TabCompleter());

        // Subtract one to count for Lukkit being loaded. Should replace with check internally because other plugins will be registered
        int totalPlugins = LuaEnvironment.loadedPlugins.size();

        if (totalPlugins > 0) {
            this.getLogger().info(((totalPlugins != 1) ? totalPlugins + " Lukkit plugins were loaded" : "1 Lukkit plugin was loaded") + " in " + loadTime + "ms.");
        } else {
            this.getLogger().info("No Lukkit plugins were loaded.");
        }
    }

    @Override
    public void onDisable() {}

    @Override
    public void onLoad() {
        // Set the logger and instance
        logger = this.getLogger();
        instance = this;

        // Create the data folder directory if it doesn't exist
        if (!this.getDataFolder().exists()) //noinspection ResultOfMethodCallIgnored
            this.getDataFolder().mkdir();

        // Check the config
        this.checkConfig();

        // Initialize the Lua env (sets up globals)
        LuaEnvironment.init(this.getConfig().getBoolean("lua-debug"));

        // Register our custom plugin loader on the plugin manager
        this.getServer().getPluginManager().registerInterface(LukkitPluginLoader.class);
        // Save the plugin manager for future use
        this.pluginManager = this.getServer().getPluginManager();

        this.getLogger().info("Loading Lukkit plugins...");

        // Get the files in the plugins directory
        File[] plugins = this.getFile().getParentFile().listFiles();

        if (plugins != null) {
            // Set the start time of loading
            long startTime = System.currentTimeMillis();

            for (File file : plugins) {
                // "break" if the file isn't for Lukkit
                if (isLukkitPluginFile(file.getName())) {
                    // Load the plugin using LukkitPluginLoader
                    try { ((LukkitPluginLoader) this.pluginManager).loadPlugin(file); }
                    catch (InvalidPluginException e) { e.printStackTrace(); }
                }
            }

            // Get the total time to load plugins and save to loadTime member
            loadTime = System.currentTimeMillis() - startTime;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().startsWith("lukkit")) {
            if (args.length != 0) {
                // Set the String "cmd" to the first arg and remove the arg from the "args" array.
                String cmd = args[0];

                args = (String[]) ArrayUtils.remove(args, 0);

                if (cmd.equalsIgnoreCase("help")) {
                    sender.sendMessage(getHelpMessage());
                } else if (cmd.equalsIgnoreCase("plugins")) {
                    StringBuilder sb = new StringBuilder().append(ChatColor.GREEN).append("Lukkit Plugins:").append(ChatColor.YELLOW);

                    this.iteratePlugins((p) -> {
                        sb.append("\n  - ").append(p.getName());
                        if (p.getDescription().getDescription() != null) {
                            sb.append(": ").append(p.getDescription().getDescription());
                        }
                    });

                    sender.sendMessage(sb.toString());
                    return true;
                } else if (cmd.equalsIgnoreCase("dev")) {
                    if (args.length == 0) {
                        sender.sendMessage(getDevHelpMessage());
                    } else if (args[0].equalsIgnoreCase("reload")) {
                        HashMap<String, LukkitPlugin> plugins = new HashMap<>();
                        this.iteratePlugins(p -> plugins.put(p.getName().toLowerCase(), p));

                        if (plugins.containsKey(args[0].toLowerCase())) {
                            // TODO
                            //plugins.get(args[2].toLowerCase()).reloadPlugin();
                        } else {
                            sender.sendMessage("The specified plugin " + args[1] + " does not exist.");
                        }
                    } else if (args[0].equalsIgnoreCase("pack")) {
                        this.zipOperation(ZipOperation.PACKAGE, sender, args);
                    } else if (args[0].equalsIgnoreCase("unpack")) {
                        this.zipOperation(ZipOperation.UNPACK, sender, args);
                    } else if (args[0].equalsIgnoreCase("last-error")) {
                        LuaError err = LuaEnvironment.getLastError();
                        if (err != null) {
                            sender.sendMessage(err.getMessage());
                            err.printStackTrace();
                        } else {
                            sender.sendMessage("There was no error to get.");
                        }
                    } else if (args[0].equalsIgnoreCase("errors")) {
                        Stream<LuaError> errors = LuaEnvironment.getErrors();
                        if (errors != null) {
                            if (args[1] == null) {
                                errors.forEach(luaError -> {
                                    sender.sendMessage(luaError.getMessage());
                                    luaError.printStackTrace();
                                });
                            } else {
                                try {
                                    LuaError error = ((LuaError[]) errors.toArray())[Integer.parseInt(args[2])];
                                    sender.sendMessage(error.getMessage());
                                    error.printStackTrace();
                                } catch (NumberFormatException e) {
                                    sender.sendMessage(ChatColor.RED + args[1] + " cannot be converted to an integer.");
                                } catch (ArrayIndexOutOfBoundsException e) {
                                    sender.sendMessage(ChatColor.RED + args[1] + " is out of bounds. Should be between 1 & 10");
                                }
                            }
                        } else {
                            sender.sendMessage("There were no errors in the stack");
                        }
                    } else sender.sendMessage(getDevHelpMessage());
                }
            } else sender.sendMessage(getHelpMessage());

            return true;
        }

        return false;
    }

    private void checkConfig() {
        // Get the config by relative path
        File cfg = new File(this.getDataFolder().getAbsolutePath() + File.separator + "config.yml");
        // Save the config if it doesn't exist
        if (!cfg.exists()) this.saveDefaultConfig();

        // Check the config version against the internal version
        if (this.getConfig().getInt("cfg-version") != CFG_VERSION) {
            this.getLogger().info("Your config is out of date. Replacing the config with the default copy and moving the old version to config.old.yml");

            // Create a new place for the old config to live
            File bkpCfg = new File(this.getDataFolder().getAbsolutePath() + File.separator + "config.old.yml");
            try {
                // Copy the config to the new path and delete the old one, essentially moving it
                Files.copy(cfg.toPath(), bkpCfg.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(cfg.toPath());
                // Save the internal config to the data folder
                this.saveDefaultConfig();
            } catch (IOException e) {
                this.getLogger().severe("There was an issue with moving the old config or replacing. Check the stacktrace for more.");
                e.printStackTrace();
            }
        }
    }

    private void iteratePlugins(Consumer<LukkitPlugin> call) {
        for (Plugin plugin : this.pluginManager.getPlugins()) {
            if (plugin instanceof LukkitPlugin) {
                call.accept((LukkitPlugin) plugin);
            }
        }
    }

    private void zipOperation(ZipOperation operation, CommandSender sender, String[] args) {
        if (args[1] != null) {
            HashMap<String, LukkitPlugin> plugins = new HashMap<>();
            this.iteratePlugins(p -> plugins.put(p.getName().toLowerCase(), p));

            LukkitPlugin plugin = plugins.get(args[1]);

            if (plugin != null) {
                if ((operation == ZipOperation.PACKAGE) == plugin.isDevPlugin()) {
                    if (operation == ZipOperation.PACKAGE) {
                        ZipUtil.unexplode(plugin.getFile());
                    } else {
                        ZipUtil.explode(plugin.getFile());
                    }
                } else {
                    sender.sendMessage("The specified plugin \"" + plugin.getName() + "\" is already " + ((operation == ZipOperation.PACKAGE) ? "packaged" : "unpacked") + ".");
                }
            } else {
                sender.sendMessage("The specified plugin \"" + args[1] + "\" does not exist.");
            }
        } else {
            sender.sendMessage("You didn't specify a plugin to " + ((operation == ZipOperation.PACKAGE) ? "package" : "unpack") + "!");
        }
    }

    private static boolean isLukkitPluginFile(String fileName) {
        for (Pattern pattern : LukkitPluginLoader.fileFilters) {
            if (pattern.matcher(fileName).find()) return true;
        }

        return false;
    }

    private static String getHelpMessage() {
        return ChatColor.GREEN + "Lukkit commands:\n" +
                ChatColor.YELLOW + "  - \"/lukkit\" - The root command for all commands (shows this message)\n" +
                "  - \"/lukkit help\" - Displays this message\n" +
                "  - \"/lukkit run (lua code)\" - Runs the specified code as command arguments\n" +
                "  - \"/lukkit plugins\" - Lists all enabled plugins\n" +
                "  - \"/lukkit dev\" - Contains all developer commands. Prints out the dev help message";
    }

    private static String getDevHelpMessage() {
        return ChatColor.GREEN + "Lukkit dev commands:\n" +
                ChatColor.YELLOW + "  - \"/lukkit dev\" - The root command for developer actions (shows this message)\n" +
                "  - \"/lukkit dev reload (plugin name)\" - Reloads the source file and clears all loaded requires\n" +
                "  - \"/lukkit dev pack (plugin name)\" - Packages the plugin (directory) into a .lkt file for publishing\n" +
                "  - \"/lukkit dev unpack (plugin name)\" - Unpacks the plugin (.lkt) to a directory based plugin\n" +
                "  - \"/lukkit dev last-error\" - Gets the last error thrown by a plugin and sends the message to the sender. Also prints the stacktrace to the console.\n" +
                "  - \"/lukkit dev errors [index]\" - Either prints out all 10 errors with stacktraces or prints out the specified error at the given index [1 - 10]\n" +
                "  - \"/lukkit dev help\" - Shows this message";
    }
}
