package com.fmcraft;

import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.LinkedHashMap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Fmcraft.MODID, dist = Dist.CLIENT)
public class Fmcraft {

    public static final String API_KEY = "a86a59c8685788615a99e57985c86942";
    public static final String API_SECRET = "1f0a042541f02f2cf839ae516a0542be";
    public static final String MODID = "fmcraft";
    public static final ModConfigSpec CONFIG_SPEC;
    public static final ModConfigSpec.IntValue CFG_MIN_SECONDS;
    public static final ModConfigSpec.IntValue CFG_MIN_PERCENT;
    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        CFG_MIN_SECONDS = b.defineInRange("minSeconds", 30, 1, 3600);
        CFG_MIN_PERCENT = b.defineInRange("minPercent", 50, 1, 100);
        CONFIG_SPEC = b.build();
    }
    private static String pendingToken = null;
    private static String sessionKey = null;
    private static String cachedUsername = null;
    private static Connection dbConnection = null;
    private static File sessionFile = null;
    private static SoundInstance currentInstance = null;
    private static SoundInstance pendingInstance = null;
    private static Song currentSong = null;
    private static long startMillis = 0L;
    private static long startUnixSeconds = 0L;
    private static int pendingTicks = 0;

    // mod entrypoint

    public Fmcraft(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(Fmcraft::onClientSetup);
        NeoForge.EVENT_BUS.addListener(Fmcraft::onPlaySound);
        NeoForge.EVENT_BUS.addListener(Fmcraft::onClientTick);
        NeoForge.EVENT_BUS.addListener(Fmcraft::onRegisterCommands);
    }

    // life

    static void onClientSetup(FMLClientSetupEvent event) {
        File configDir = FMLPaths.CONFIGDIR.get().toFile();
        openDatabase(configDir);
        loadSession(configDir);
    }

    public static class Song {
        public String resourceLocation, title, artist, album, category, source;
        public int durationSeconds;
    }

    private static void openDatabase(File configDir) {
        File dbFile = new File(configDir, "fmcraft.db");
        if (!dbFile.exists()) extractDefaultDb(dbFile);
        try {
            DriverManager.registerDriver(new org.sqlite.JDBC());
            dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (Exception e) {dbConnection = null;}
    }

    private static void extractDefaultDb(File target) {
        try (InputStream in = Fmcraft.class.getResourceAsStream("/fmcraft.db")) {
            if (in == null) return;
            target.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[4096];
                int q;
                while ((q = in.read(buf)) >= 0) out.write(buf, 0, q);
            }
        } catch (Exception e) {}
    }

    private static Song lookupSong(String resourceLocation) {
        if (dbConnection == null) return null;
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "SELECT resource_location, title, artist, album, duration_seconds, category, source FROM songs WHERE resource_location = ?")) {
            ps.setString(1, resourceLocation);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Song s = new Song();
                s.resourceLocation = rs.getString(1);
                s.title = rs.getString(2);
                s.artist = rs.getString(3);
                s.album = rs.getString(4);
                s.durationSeconds = rs.getInt(5);
                if (rs.wasNull()) s.durationSeconds = 0;
                s.category = rs.getString(6);
                s.source = rs.getString(7);
                return s;
            }
        } catch (Exception e) { return null; }
    }

    // session file

    private static void loadSession(File configDir) {
        sessionFile = new File(configDir, "fmcraft_session.txt");
        sessionKey = null;
        if (!sessionFile.exists()) return;
            try (BufferedReader r = new BufferedReader(new FileReader(sessionFile))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) sessionKey = line.trim();
            } catch (Exception e) {
            }
    }

    private static void saveSession(String key) {
        sessionKey = key;
        if (sessionFile == null) return;
            try {
                sessionFile.getParentFile().mkdirs();
                try (FileWriter w = new FileWriter(sessionFile)) {
                    w.write(key);
                    w.write("\n");
                }
            } catch (Exception e) {
                // not sure what to put here
            }
    }

    // last.fm api

    private static final String LASTFM_ENDPOINT = "https://ws.audioscrobbler.com/2.0/";

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sign(Map<String, String> params, String secret) {
        return md5Hex(
            params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + e.getValue())
                .reduce("", String::concat)
            + secret
        );
    }

    private static String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
            .map(e -> {
                try {
                    return URLEncoder.encode(e.getKey(), "UTF-8") + "=" + URLEncoder.encode(e.getValue(), "UTF-8"); 
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static String httpGet(Map<String, String> params) throws Exception {
        String url = LASTFM_ENDPOINT + "?" + encodeForm(params);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "";
        conn.disconnect();
        if (code < 200 || code >= 300)
            throw new RuntimeException("HTTP " + code + ": " + body);
        return body;
    }

    private static String httpPost(Map<String, String> params) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(LASTFM_ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = encodeForm(params).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) { out.write(body); }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String resp = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "";
        conn.disconnect();
        if (code < 200 || code >= 300)
            throw new RuntimeException("HTTP " + code + ": " + resp);
        return resp;
    }

    private static String extractXmlTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int a = xml.indexOf(open);
        if (a < 0) return null;
        int b = xml.indexOf(close, a);
        if (b < 0) return null;
        return xml.substring(a + open.length(), b);
    }

    private static String apiGetToken(String apiKey, String apiSecret) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "auth.getToken");
        p.put("api_key", apiKey);
           p.put("api_sig", sign(p, apiSecret));
        String body = httpGet(p);
        String token = extractXmlTag(body, "token");
        if (token == null)
            throw new RuntimeException("no token in response: " + body);
        return token;
    }

    private static String apiGetSession(String apiKey, String apiSecret, String token) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "auth.getSession");
        p.put("api_key", apiKey);
        p.put("token", token);
        p.put("api_sig", sign(p, apiSecret));
        String body = httpGet(p);
        String key = extractXmlTag(body, "key");
        if (key == null)
            throw new RuntimeException("no session key in respons: " + body);
        return key;
    }

    private static String apiGetUsername(String apiKey, String apiSecret, String sk) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "user.getInfo");
        p.put("api_key", apiKey);
        p.put("sk", sk);
        p.put("api_sig", sign(p, apiSecret));
        String body = httpGet(p);
        String username = extractXmlTag(body, "name");
        if (username == null) {
            throw new RuntimeException("no username in response: " + body);
        }
        return username;
    }

    private static void apiNowPlaying(String apiKey, String apiSecret, String sk, Song song) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "track.updateNowPlaying");
        p.put("api_key", apiKey);
        p.put("sk", sk);
        p.put("artist", song.artist);
        p.put("track", song.title);
        if (song.album != null && !song.album.isEmpty()) {
            p.put("album", song.album);
        }
        if (song.durationSeconds > 0) {
            p.put("duration", String.valueOf(song.durationSeconds));
        }
        p.put("api_sig", sign(p, apiSecret));
        httpPost(p);
    }

    private static void apiScrobble(String apiKey, String apiSecret, String sk, Song song, long ts) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "track.scrobble");
        p.put("api_key", apiKey);
        p.put("sk", sk);
        p.put("artist", song.artist);
        p.put("track", song.title);
        p.put("timestamp", String.valueOf(ts));
        if (song.album != null && !song.album.isEmpty()) {
            p.put("album", song.album);
        }
        if (song.durationSeconds > 0) {
            p.put("duration", String.valueOf(song.durationSeconds));
        }
        p.put("api_sig", sign(p, apiSecret));
        httpPost(p);
    }

    // scrobbling

    private static void scrobbleAsync(Song song, long startUnix, boolean nowPlayingOnly) {
        String sk = sessionKey;
        if (sk == null || sk.isEmpty()) {
            sendChat("Run /fmcraft login to log in");
            return;
        }
        if (song.artist == null || song.artist.trim().isEmpty()) {
            sendChat("Scrobble skipped: no artist found for " + song.resourceLocation);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (nowPlayingOnly) {
                    apiNowPlaying(API_KEY, API_SECRET, sk, song);
                } else {
                    apiScrobble(API_KEY, API_SECRET, sk, song, startUnix);
                }
            } catch (Exception e) {
                sendChat("Last.fm request failed: " + e.getMessage());
            }
        }, "fmcraft-scrobble");
        t.setDaemon(true); // not quite sure what this does but someone online said i needed it
        t.start();
    }

    // audio tracker

    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance instance = event.getSound();
        if (instance == null) return;
        SoundSource src = instance.getSource();
        if (src != SoundSource.MUSIC && src != SoundSource.RECORDS) return;
        pendingInstance = instance;
        pendingTicks = 0;
    }

    private static void tryResolvePending() {
        SoundInstance inst = pendingInstance;
        if (inst == null) return;
        net.minecraft.client.resources.sounds.Sound sound = inst.getSound();
        if (sound == null) {
            pendingTicks++;
            if (pendingTicks > 40) {
                pendingInstance = null;
                pendingTicks = 0;
            }
            return;
        }
        ResourceLocation rl = sound.getLocation();
        String path = rl.getPath();
        if (path.startsWith("sounds/")) path = path.substring("sounds/".length());
        String loc = rl.getNamespace() + ":" + path;
        Song s = lookupSong(loc);
        SoundInstance instCopy = inst;
        pendingInstance = null;
        pendingTicks = 0;
        if (s == null) {
            return;
        }
        if (currentInstance != null) {
            SoundSource oldSrc = currentInstance.getSource();
            SoundSource newSrc = instCopy.getSource();
            if (oldSrc == newSrc) {
                return;
            }
            finishCurrent();
        }
        currentInstance = instCopy;
        currentSong = s;
        startMillis = System.currentTimeMillis();
        startUnixSeconds = startMillis / 1000L;
        scrobbleAsync(s, startUnixSeconds, true);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingInstance != null) tryResolvePending();
        if (currentInstance == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.getSoundManager().isActive(currentInstance)) return;
        finishCurrent();
    }

    private static void finishCurrent() {
        Song s = currentSong;
        long start = startUnixSeconds;
        long started = startMillis;
        currentInstance = null;
        currentSong = null;
        startMillis = 0L;
        startUnixSeconds = 0L;
        if (s == null) return;
        long elapsedSec = (System.currentTimeMillis() - started) / 1000L;
        int minSec = CFG_MIN_SECONDS.get().intValue();
        int minPct = CFG_MIN_PERCENT.get().intValue();
        if (elapsedSec < (long) minSec) return;
        if (s.durationSeconds > 0) {
            long needed = ((long) s.durationSeconds * (long) minPct) / 100L;
            if (elapsedSec < needed) return;
        }
        scrobbleAsync(s, start, false);
    }

    // ingame commands

    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.literal("fmcraft");
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("login")
                .executes((CommandContext<CommandSourceStack> ctx) -> { doLogin(); return 1; }));
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("confirm")
                .executes((CommandContext<CommandSourceStack> ctx) -> { doConfirm(); return 1; }));
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                .executes((CommandContext<CommandSourceStack> ctx) -> { doStatus(); return 1; }));
        d.register(root);
    }

    private static void sendChat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LocalPlayer p = mc.player;
        if (p == null) { return; }
        p.displayClientMessage(Component.literal("[FMCraft] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(msg).withStyle(ChatFormatting.WHITE)), false);
    }

    private static void sendChatLink(String label, String url) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LocalPlayer p = mc.player;
        if (p == null) { return; }
        Style linkStyle = Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withUnderlined(Boolean.TRUE)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        p.displayClientMessage(Component.literal("[FMCraft] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(label).withStyle(linkStyle)), false);
    }

    private static void doLogin() {
        Thread t = new Thread(() -> {
            try {
                String token = apiGetToken(API_KEY, API_SECRET);
                pendingToken = token;
                String url = "http://www.last.fm/api/auth/?api_key=" + API_KEY + "&token=" + token;
                sendChatLink("Click to authorize with last.fm", url);
                sendChat("Then run /fmcraft confirm");
            } catch (Exception e) {
                    sendChat("Login failed");
            }
        }, "fmcraft-login");
        t.setDaemon(true); // just to be safe
        t.start();
    }

    private static void doConfirm() {
        final String token = pendingToken;
        if (token == null) { sendChat("Run /fmcraft login first"); return; }
        Thread t = new Thread(() -> {
            try {
                String key = apiGetSession(API_KEY, API_SECRET, token);
                saveSession(key);
                pendingToken = null;
                sendChat("Scrobbling enabled");
            } catch (Exception e) {
                    sendChat("Confirmation failed");
            }
        }, "fmcraft-confirm");
        t.setDaemon(true);
        t.start();
    }

    private static void doStatus() {
        if (sessionKey == null || sessionKey.isEmpty()) {
            sendChat("sesion key missing");
            showNowPlaying();
            return;
        }
        if (cachedUsername == null) {
            Thread t = new Thread(() -> {
                try {
                    cachedUsername = apiGetUsername(API_KEY, API_SECRET, sessionKey);
                    sendChat("Logged in as: " + cachedUsername);
                } catch (Exception e) {
                        sendChat("Could not get username");
                }
                showNowPlaying();
            }, "fmcraft-status-username");
            t.setDaemon(true);
            t.start();
            return;
        }
        sendChat("Logged in as: " + cachedUsername);
        showNowPlaying();
    }

    private static void showNowPlaying() {
        Song s = currentSong;
        if (s == null) {
            sendChat("nothing is playing");
        } else {
            sendChat("now playing: " + s.artist + " - " + s.title);
        }
    }
}