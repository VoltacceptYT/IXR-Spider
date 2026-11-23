package net.browselotus.spiderbot.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PackSender implements Listener, TabExecutor {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    public PackSender(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static final String TOKEN_PREFIX = "/pack/";

    private volatile Path packFile;
    private volatile byte[] packHash;
    private volatile UUID staticPackId;
    /** Null = auto-detect per player from the address they joined with. */
    private volatile String baseUrl;
    private volatile int httpPort;
    private volatile boolean sharedMode;
    private GamePortShare share;

    /** token -> expiry (epoch millis) */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    /** player -> pack UUID most recently sent to them */
    private final Map<UUID, UUID> playerPackIds = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        PluginCommand command = plugin.getCommand("spiderpack");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        loadPack();
    }

    public void disable() {
        stopHttpServer();
        if (share != null) {
            share.close();
            share = null;
        }
        tokens.clear();
    }

    // ------------------------------------------------------------------ loading

    /** (Re)loads config, hashes the pack and (re)starts the web server. */
    private FileConfiguration getConfig() { return config; }

    private java.util.logging.Logger getLogger() { return plugin.getLogger(); }

    private boolean loadPack() {
        stopHttpServer();
        packFile = null;
        tokens.clear();

        File configFile = new File(plugin.getDataFolder(), "resource-pack.yml");
        if (!configFile.exists()) {
            plugin.saveResource("resource-pack.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        String fileName = getConfig().getString("resource-pack.file", "pack.zip");
        Path file = plugin.getDataFolder().toPath().resolve(fileName).normalize();

        // The pack is the "pack" folder inside the plugin jar; zip it up fresh each load.
        try {
            buildPackZip(file);
        } catch (Exception e) {
            getLogger().severe("Could not build the resource pack from the plugin's pack folder: " + e);
            return false;
        }

        if (!Files.isRegularFile(file)) {
            getLogger().warning("Resource pack not found: " + file.toAbsolutePath());
            getLogger().warning("Put your pack there and run /spiderpack reload");
            return false;
        }

        try {
            byte[] hash = sha1(file);

            boolean wantShare = getConfig().getBoolean("http.share-game-port", true);
            if (wantShare && share == null) {
                try {
                    GamePortShare candidate = new GamePortShare(this);
                    candidate.inject();
                    share = candidate;
                } catch (Throwable t) {
                    getLogger().warning("Could not share the game port (" + t
                            + "); falling back to a separate web server on http.port.");
                }
            } else if (!wantShare && share != null) {
                share.close();
                share = null;
            }
            this.sharedMode = share != null;

            int port = sharedMode ? Bukkit.getPort() : getConfig().getInt("http.port", 8080);

            String base = getConfig().getString("http.public-url", "");
            if (base == null || base.isBlank()) {
                String host = getConfig().getString("http.public-host", "");
                base = (host == null || host.isBlank()) ? null : "http://" + host + ":" + port;
            }
            while (base != null && base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }

            this.packFile = file;
            this.packHash = hash;
            this.staticPackId = UUID.nameUUIDFromBytes(hash);
            this.baseUrl = base;
            this.httpPort = port;

            if (!sharedMode) {
                startHttpServer(getConfig().getString("http.bind-address", "0.0.0.0"), port);
            }
            getLogger().info("Serving " + file.getFileName() + " (sha1 " + HexFormat.of().formatHex(hash)
                    + ") on " + (sharedMode ? "the game port " + port + " (shared)" : "port " + port)
                    + (base == null ? ", URL auto-detected from the address each player joins with" : " at " + base));
            return true;
        } catch (Exception e) {
            this.packFile = null;
            getLogger().severe("Failed to load resource pack: " + e);
            return false;
        }
    }

    /** Zips every file under the jar's "pack/" folder into {@code target}, with the folder's contents at the zip root. */
    private void buildPackZip(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        File jarFile = new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(jarFile);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            List<JarEntry> entries = jar.stream()
                    .filter(e -> !e.isDirectory() && e.getName().startsWith("pack/"))
                    .sorted(java.util.Comparator.comparing(JarEntry::getName))
                    .toList();
            if (entries.isEmpty()) {
                throw new IllegalStateException("no files found under pack/ in the plugin jar");
            }
            for (JarEntry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.getName().substring("pack/".length()));
                zipEntry.setTime(0); // fixed timestamp so the hash is stable between restarts
                out.putNextEntry(zipEntry);
                try (InputStream in = jar.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }

    private static byte[] sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    // -------------------------------------------------------------- web server

    private void startHttpServer(String bindAddress, int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        httpExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "SpiderPack-HTTP");
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(httpExecutor);
        httpServer.createContext("/pack.zip", this::handleStatic);
        httpServer.createContext(TOKEN_PREFIX, this::handleToken);
        httpServer.start();
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/pack.zip")) {
            try (exchange) {
                exchange.sendResponseHeaders(404, -1);
            }
            return;
        }
        serve(exchange);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String token = path.substring(TOKEN_PREFIX.length());
        if (token.endsWith(".zip")) {
            token = token.substring(0, token.length() - 4);
        }

        Long expiry = tokens.get(token);
        if (expiry == null || expiry < System.currentTimeMillis()) {
            tokens.remove(token);
            try (exchange) {
                exchange.sendResponseHeaders(404, -1);
            }
            return;
        }
        serve(exchange);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = method.equalsIgnoreCase("HEAD");
            if (!head && !method.equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Path file = packFile;
            if (file == null || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            long size = Files.size(file);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");

            if (head) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
        }
    }

    /** Maps a request path to the pack file if it's a valid, unexpired URL; otherwise null. */
    Path resolveRequest(String path) {
        Path file = packFile;
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        if (path.equals("/pack.zip")) {
            return file;
        }
        if (path.startsWith(TOKEN_PREFIX)) {
            String token = path.substring(TOKEN_PREFIX.length());
            if (token.endsWith(".zip")) {
                token = token.substring(0, token.length() - 4);
            }
            Long expiry = tokens.get(token);
            if (expiry != null && expiry >= System.currentTimeMillis()) {
                return file;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- sending

    /** Base URL for this player: config override, else the address they connected to. */
    private String resolveBase(Player player) {
        if (baseUrl != null) {
            return baseUrl;
        }
        String host = null;
        int port = httpPort;
        InetSocketAddress virtual = player.getVirtualHost();
        if (virtual != null) {
            host = virtual.getHostString();
            if (sharedMode && virtual.getPort() > 0) {
                port = virtual.getPort(); // the port the client actually connected to
            }
        }
        if (host == null || host.isBlank()) {
            host = Bukkit.getIp(); // server-ip from server.properties
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }
        return "http://" + host + ":" + port;
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void pruneTokens() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(expiry -> expiry < now);
    }

    private boolean sendPack(Player player) {
        if (packFile == null) {
            return false;
        }

        String base = resolveBase(player);
        UUID id;
        String url;
        if (getConfig().getBoolean("resource-pack.per-join-urls", true)) {
            pruneTokens();
            long ttl = Math.max(30, getConfig().getLong("resource-pack.per-join-ttl-seconds", 600)) * 1000L;
            String token = newToken();
            tokens.put(token, System.currentTimeMillis() + ttl);
            id = UUID.randomUUID();
            url = base + TOKEN_PREFIX + token + ".zip";
        } else {
            id = staticPackId;
            url = base + "/pack.zip";
        }

        String promptText = getConfig().getString("resource-pack.prompt", "");
        Component prompt = (promptText == null || promptText.isBlank())
                ? null
                : MiniMessage.miniMessage().deserialize(promptText);
        boolean force = getConfig().getBoolean("resource-pack.force", false);

        playerPackIds.put(player.getUniqueId(), id);
        player.setResourcePack(id, url, packHash, prompt, force);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("resource-pack.send-on-join", true)) {
            return;
        }
        long delay = Math.max(1, getConfig().getLong("resource-pack.join-delay-ticks", 20));
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendPack(player);
            }
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerPackIds.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        UUID expected = playerPackIds.get(event.getPlayer().getUniqueId());
        if (expected == null || !expected.equals(event.getID())) {
            return;
        }
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD -> getLogger().warning(event.getPlayer().getName()
                    + " failed to download the pack - make sure TCP port " + httpPort
                    + " is reachable from the player and the URL hasn't expired.");
            case INVALID_URL, FAILED_RELOAD, DISCARDED -> getLogger().warning(
                    event.getPlayer().getName() + " pack status: " + event.getStatus());
            default -> { }
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("spiderbot.pack.admin")) {
            sender.sendMessage(Component.text("You don't have permission."));
            return true;
        }

        String sub = args.length == 0 ? "send" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                boolean ok = loadPack();
                sender.sendMessage(Component.text(ok
                        ? "Resource pack reloaded. Use /spiderpack send to resend it."
                        : "Failed to load the pack, see console."));
            }
            case "send" -> {
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Player not found: " + args[1]));
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(Component.text("Usage: /" + label + " send <player>"));
                    return true;
                }
                sender.sendMessage(Component.text(sendPack(target)
                        ? "Sent the resource pack to " + target.getName() + "."
                        : "No pack loaded. Check the console."));
            }
            default -> sender.sendMessage(Component.text("Usage: /" + label + " <send [player]|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("spiderbot.pack.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("send", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("send")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
