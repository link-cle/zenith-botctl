package com.botctl.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * BotCtl 面板：只依赖 ZenithProxy 本体，不依赖任何第三方面板插件。
 *
 * 端口、路径、Cookie 名、JSON 结构与旧 WebUI 保持兼容，所以手机 App 不用改就能连。
 * 地图静态文件、删图、定时喊话也都在这里，不再需要额外的 8081 服务。
 */
public final class Panel {

    private final JsonObject cfg;
    private final Path installDir;
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private HttpServer server;
    private ThreadPoolExecutor pool;

    public Panel(JsonObject cfg, Path installDir) {
        this.cfg = cfg;
        this.installDir = installDir;
    }

    public void start() throws IOException {
        int port = cfg.has("port") ? cfg.get("port").getAsInt() : 8080;
        server = HttpServer.create(new InetSocketAddress(port), 64);
        pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.createContext("/", new Router());
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(1);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 路由

    private final class Router implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                route(ex);
            } catch (Throwable t) {
                try {
                    send(ex, 500, json("error", "内部错误: " + t));
                } catch (Throwable ignored) {
                }
            } finally {
                ex.close();
            }
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        // POST 的 body 只读一次（后面鉴权和各 handler 共用）
        JsonObject reqBody = "POST".equals(method) ? body(ex) : new JsonObject();

        if (path.equals("/api/health")) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("service", "botctl-panel");
            o.addProperty("logs", Logs.size());
            o.addProperty("maps", countMaps());
            send(ex, 200, o);
            return;
        }
        if (path.equals("/api/auth/login") && "POST".equals(method)) {
            handleLogin(ex, reqBody);
            return;
        }
        if (path.equals("/api/auth/logout")) {
            String tok = tokenOf(ex);
            if (tok != null) tokens.remove(tok);
            send(ex, 200, json("success", true));
            return;
        }
        if (path.equals("/api/auth/status")) {
            JsonObject o = new JsonObject();
            o.addProperty("authenticated", authed(ex));
            o.addProperty("username", cfg.has("username") ? cfg.get("username").getAsString() : "admin");
            o.addProperty("authEnabled", true);
            send(ex, 200, o);
            return;
        }

        // 地图静态文件不需要登录（手机 <img> 拿不到 Cookie 也行，和旧实现一致）
        if (path.startsWith("/maps/")) {
            serveMap(ex, path.substring("/maps/".length()));
            return;
        }

        if (!authed(ex) && !credsOk(reqBody)) {
            send(ex, 401, json("error", "未认证：Cookie 失效且请求体里没有正确的账号密码"));
            return;
        }

        if (path.equals("/api/dashboard")) {
            send(ex, 200, Cached.dashboard(this));
            return;
        }
        if (path.equals("/api/commands") && "POST".equals(method)) {
            String cmd = reqBody.has("command") ? reqBody.get("command").getAsString() : "";
            List<String> out = Probe.command(cmd);
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            JsonArray arr = new JsonArray();
            for (String s : out) arr.add(s);
            o.add("output", arr);
            send(ex, 200, o);
            return;
        }
        if (path.equals("/api/logs/stream")) {
            streamLogs(ex);
            return;
        }
        if (path.equals("/api/whitelist")) {
            if ("POST".equals(method)) {
                handleWhitelistAction(ex);
            } else {
                send(ex, 200, whitelist());
            }
            return;
        }

        // ---- 地图维护（旧 8081 的接口，搬到面板端口）
        if (path.equals("/api/maps/delete") && "POST".equals(method)) {
            int n = 0;
            if (reqBody.has("names") && reqBody.get("names").isJsonArray()) {
                for (com.google.gson.JsonElement el : reqBody.getAsJsonArray("names")) {
                    if (deleteMap(el.getAsString())) n++;
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            o.addProperty("deleted", n);
            send(ex, 200, o);
            return;
        }
        if (path.equals("/api/maps/clear") && "POST".equals(method)) {
            int n = 0;
            for (String name : listMaps()) if (deleteMap(name)) n++;
            JsonObject o = new JsonObject();
            o.addProperty("success", true);
            o.addProperty("deleted", n);
            send(ex, 200, o);
            return;
        }

        // ---- 定时喊话（旧 8081 的接口）
        if (path.startsWith("/api/chat/")) {
            handleChat(ex, path.substring("/api/chat/".length()), reqBody);
            return;
        }

        send(ex, 404, json("error", "not found"));
    }

    // ------------------------------------------------------------------ 认证

    private void handleLogin(HttpExchange ex, JsonObject body) throws IOException {
        String u = body.has("username") ? body.get("username").getAsString() : "";
        String p = body.has("password") ? body.get("password").getAsString() : "";
        String cu = cfg.has("username") ? cfg.get("username").getAsString() : "admin";
        String cp = cfg.has("password") ? cfg.get("password").getAsString() : "";
        if (!cu.equals(u) || !cp.equals(p)) {
            send(ex, 401, json("error", "用户名或密码不对"));
            return;
        }
        String token = UUID.randomUUID().toString();
        long ttl = cfg.has("tokenExpiryMs") ? cfg.get("tokenExpiryMs").getAsLong() : 86400000L;
        tokens.put(token, System.currentTimeMillis() + ttl);
        ex.getResponseHeaders().add("Set-Cookie",
                "webui_token=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (ttl / 1000));
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("username", cu);
        o.addProperty("token", token);
        send(ex, 200, o);
    }

    private String tokenOf(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String s = part.trim();
                if (s.startsWith("webui_token=")) return s.substring("webui_token=".length());
            }
        }
        return null;
    }

    /** 请求体里带 panel 账号密码也算通过（兼容 App 的地图/喊话调用）。 */
    private boolean credsOk(JsonObject body) {
        try {
            if (!body.has("user") || !body.has("pass")) return false;
            String cu = cfg.has("username") ? cfg.get("username").getAsString() : "admin";
            String cp = cfg.has("password") ? cfg.get("password").getAsString() : "";
            return cu.equals(body.get("user").getAsString()) && cp.equals(body.get("pass").getAsString());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean authed(HttpExchange ex) {
        String tok = tokenOf(ex);
        if (tok == null) return false;
        Long exp = tokens.get(tok);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) {
            tokens.remove(tok);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 数据

    private JsonObject dashboardNow() {
        Map<String, String> st = Probe.status();
        JsonObject o = new JsonObject();
        boolean online = "Online".equalsIgnoreCase(st.getOrDefault("status", ""));
        o.addProperty("connected", online);
        o.addProperty("inQueue", false);
        o.addProperty("queuePosition", 0);
        o.addProperty("queueOnlinePlayers", 0);
        o.addProperty("queuePos", "");
        o.addProperty("username", st.getOrDefault("username", "-"));
        o.addProperty("proxyAddress", st.getOrDefault("proxyIp", "-"));
        o.addProperty("proxyIp", st.getOrDefault("proxyIp", ""));
        o.addProperty("uptime", st.getOrDefault("uptime", ""));
        o.addProperty("onlineDuration", st.getOrDefault("onlineDuration", ""));
        String via = Cached.via();
        o.addProperty("version", st.getOrDefault("build", "unknown"));
        o.addProperty("viaVersion", via);

        JsonObject p = new JsonObject();
        p.addProperty("connected", online);
        double health = -1;
        try {
            health = Double.parseDouble(st.getOrDefault("health", "-1"));
        } catch (Exception ignored) {
        }
        if (health < 0) health = Probe.healthFromCache();
        p.addProperty("health", health);
        p.addProperty("food", Probe.food());
        JsonObject pos = new JsonObject();
        pos.addProperty("x", num(st.get("x")));
        pos.addProperty("y", num(st.get("y")));
        pos.addProperty("z", num(st.get("z")));
        p.add("position", pos);
        p.addProperty("dimension", st.getOrDefault("dimension", ""));
        o.add("player", p);

        JsonObject lat = new JsonObject();
        int ping = -1;
        try {
            ping = Integer.parseInt(st.getOrDefault("ping", "-1"));
        } catch (Exception ignored) {
        }
        lat.addProperty("botLatency", ping);
        JsonArray players = new JsonArray();
        lat.add("players", players);
        o.add("latency", lat);

        double[] sys = Probe.system();
        JsonObject s = new JsonObject();
        s.addProperty("cpuLoad", sys[0]);
        s.addProperty("memoryPercent", sys[1]);
        s.addProperty("netBytesPerSecIn", sys[2]);
        s.addProperty("netBytesPerSecOut", sys[3]);
        o.add("system", s);
        return o;
    }

    private static Number num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** status / via 都是秒级缓存，避免 App 每 2 秒刷新就打一堆命令。 */
    private static final class Cached {
        private static long statusAt = 0;
        private static Map<String, String> statusCache = Map.of();
        private static long viaAt = 0;
        private static String viaCache = "";
        private static long dashAt = 0;
        private static com.google.gson.JsonObject dashCache = new com.google.gson.JsonObject();

        static synchronized JsonObject dashboard(Panel p) {
            long now = System.currentTimeMillis();
            if (now - dashAt < 2000 && dashCache.size() > 0) return dashCache;
            dashCache = p.dashboardNow();
            dashAt = now;
            return dashCache;
        }

        static synchronized String via() {
            long now = System.currentTimeMillis();
            if (now - viaAt > 20000) {
                viaCache = Probe.viaVersion();
                viaAt = now;
            }
            return viaCache;
        }
    }

    private JsonObject whitelist() {
        String[] wc = Probe.whitelistConfig();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", "true".equalsIgnoreCase(wc[0]));
        o.addProperty("kickmsg", wc[1]);
        JsonArray arr = new JsonArray();
        for (Map<String, String> e : Probe.whitelistEntries()) {
            JsonObject item = new JsonObject();
            item.addProperty("username", e.get("username"));
            item.addProperty("uuid", e.get("uuid"));
            arr.add(item);
        }
        o.add("whitelist", arr);
        return o;
    }

    private void handleWhitelistAction(HttpExchange ex) throws IOException {
        JsonObject reqBody = body(ex);
        String action = reqBody.has("action") ? reqBody.get("action").getAsString() : "";
        String user = reqBody.has("username") ? reqBody.get("username").getAsString() : "";
        boolean ok;
        if ("add".equalsIgnoreCase(action)) {
            ok = Probe.whitelistAdd(user);
        } else if ("remove".equalsIgnoreCase(action) || "del".equalsIgnoreCase(action)) {
            ok = Probe.whitelistRemove(user);
        } else {
            send(ex, 400, json("error", "action 只能是 add / remove"));
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("success", ok);
        if (!ok) o.addProperty("error", "操作失败（离线名需要写配置文件）");
        send(ex, ok ? 200 : 500, o);
    }

    // ------------------------------------------------------------------ 日志流

    private void streamLogs(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        Logs.Client client = Logs.subscribe();
        try {
            while (true) {
                String line = client.queue.poll(15, TimeUnit.SECONDS);
                if (line == null) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) {
            // 客户端断开
        } finally {
            Logs.unsubscribe(client);
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 地图

    private Path mapsDir() {
        return installDir.resolve("maps");
    }

    private List<String> listMaps() {
        List<String> out = new ArrayList<>();
        try {
            Files.list(mapsDir()).forEach(p -> out.add(p.getFileName().toString()));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private int countMaps() {
        int n = 0;
        for (String s : listMaps()) if (s.endsWith(".png")) n++;
        return n;
    }

    private void serveMap(HttpExchange ex, String name) throws IOException {
        // 只允许 maps 目录下的普通文件名，防目录穿越
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            send(ex, 404, json("error", "not found"));
            return;
        }
        Path p = mapsDir().resolve(name);
        if (!p.normalize().startsWith(mapsDir().normalize()) || !Files.isRegularFile(p)) {
            send(ex, 404, json("error", "not found"));
            return;
        }
        byte[] data = Files.readAllBytes(p);
        String ct = name.endsWith(".png") ? "image/png" : "application/json; charset=utf-8";
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private boolean deleteMap(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.\\-]+\\.png")) return false;
        try {
            return Files.deleteIfExists(mapsDir().resolve(name));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 喊话

    private void handleChat(HttpExchange ex, String action, JsonObject reqBody) throws IOException {
        Chat chat = Chat.get();
        if ("get".equals(action)) {
            send(ex, 200, chat.state());
            return;
        }
        if ("set".equals(action)) {
            send(ex, 200, chat.set(reqBody));
            return;
        }
        if ("send".equals(action)) {
            JsonObject body = reqBody;
            Integer idx = null;
            if (body.has("index")) {
                com.google.gson.JsonElement ie = body.get("index");
                if (ie != null && ie.isJsonPrimitive() && ie.getAsJsonPrimitive().isNumber()) {
                    idx = ie.getAsInt();
                }
            }
            JsonObject res = chat.sendNow(idx);
            send(ex, res.has("success") && res.get("success").getAsBoolean() ? 200 : 502, res);
            return;
        }
        if ("test".equals(action)) {
            List<String> out = Probe.command("stats");
            JsonObject o = new JsonObject();
            boolean ok = !out.isEmpty();
            o.addProperty("success", ok);
            o.addProperty("online", Probe.status().containsKey("status"));
            o.addProperty("output", String.join("\n", out));
            send(ex, ok ? 200 : 502, o);
            return;
        }
        send(ex, 404, json("error", "not found"));
    }

    // ------------------------------------------------------------------ 工具

    private static JsonObject json(String k, Object v) {
        JsonObject o = new JsonObject();
        if (v instanceof Boolean) o.addProperty(k, (Boolean) v);
        else if (v instanceof Number) o.addProperty(k, (Number) v);
        else o.addProperty(k, String.valueOf(v));
        return o;
    }

    private JsonObject body(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            if (s.isBlank()) return new JsonObject();
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    private void send(HttpExchange ex, int code, JsonObject o) throws IOException {
        byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
