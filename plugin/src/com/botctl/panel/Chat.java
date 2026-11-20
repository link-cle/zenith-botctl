package com.botctl.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 定时喊话（原来在 8081 的 Python 服务里，现在并进插件）。
 *
 * 规则与之前一致：
 *  - 间隔 + 随机抖动，最小 30 秒（防手滑刷屏）
 *  - 消息按顺序轮播
 *  - 每条末尾补一个随机小写字母，且不与上一条重复（防被拦重复消息）
 *  - 机器人离线时停发，30 秒后重试
 */
public final class Chat {

    private static final int MIN_INTERVAL = 30;
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_TEXT = 200;

    private static Chat INSTANCE;

    private final Path file;
    private final Random rnd = new Random();

    private boolean enabled = false;
    private int intervalSec = 300;
    private int jitterSec = 30;
    private boolean rotate = true;
    private boolean letterSuffix = true;
    private List<String> messages = new ArrayList<>();
    private int idx = 0;
    private long nextAt = 0;
    private long lastSent = 0;
    private String lastText = "";
    private int sentCount = 0;
    private String lastSuffix = "";

    private Chat(Path file) {
        this.file = file;
        load();
    }

    public static synchronized Chat get() {
        return INSTANCE;
    }

    public static synchronized void init(Path installDir) {
        if (INSTANCE == null) INSTANCE = new Chat(installDir.resolve("chatloop.json"));
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "botctl-chat");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ 持久化

    private synchronized void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
            if (o.has("intervalSec")) intervalSec = Math.max(MIN_INTERVAL, o.get("intervalSec").getAsInt());
            if (o.has("jitterSec")) jitterSec = Math.max(0, o.get("jitterSec").getAsInt());
            if (o.has("rotate")) rotate = o.get("rotate").getAsBoolean();
            if (o.has("letterSuffix")) letterSuffix = o.get("letterSuffix").getAsBoolean();
            if (o.has("nextAt")) nextAt = o.get("nextAt").getAsLong();
            if (o.has("lastSent")) lastSent = o.get("lastSent").getAsLong();
            if (o.has("lastText")) lastText = o.get("lastText").getAsString();
            if (o.has("sentCount")) sentCount = o.get("sentCount").getAsInt();
            if (o.has("idx")) idx = o.get("idx").getAsInt();
            if (o.has("messages") && o.get("messages").isJsonArray()) {
                messages = new ArrayList<>();
                for (JsonElement el : o.getAsJsonArray("messages")) {
                    if (!el.isJsonNull()) messages.add(el.getAsString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("intervalSec", intervalSec);
            o.addProperty("jitterSec", jitterSec);
            o.addProperty("rotate", rotate);
            o.addProperty("letterSuffix", letterSuffix);
            o.addProperty("idx", idx);
            o.addProperty("nextAt", nextAt);
            o.addProperty("lastSent", lastSent);
            o.addProperty("lastText", lastText);
            o.addProperty("sentCount", sentCount);
            JsonArray arr = new JsonArray();
            for (String m : messages) arr.add(m);
            o.add("messages", arr);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 状态

    public synchronized JsonObject state() {
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("enabled", enabled);
        o.addProperty("intervalSec", intervalSec);
        o.addProperty("jitterSec", jitterSec);
        o.addProperty("rotate", rotate);
        o.addProperty("letterSuffix", letterSuffix);
        o.addProperty("count", messages.size());
        o.addProperty("idx", idx);
        o.addProperty("nextAt", nextAt / 1000.0);
        o.addProperty("lastSent", lastSent / 1000.0);
        o.addProperty("lastText", lastText);
        o.addProperty("sentCount", sentCount);
        o.addProperty("now", System.currentTimeMillis() / 1000.0);
        o.addProperty("minInterval", MIN_INTERVAL);
        JsonArray arr = new JsonArray();
        for (String m : messages) arr.add(m);
        o.add("messages", arr);
        return o;
    }

    public synchronized JsonObject set(JsonObject body) {
        JsonObject o = new JsonObject();
        if (body.has("messages") && body.get("messages").isJsonArray()) {
            List<String> clean = new ArrayList<>();
            for (JsonElement el : body.getAsJsonArray("messages")) {
                if (el.isJsonNull()) continue;
                String s = el.getAsString().trim();
                if (!s.isEmpty()) clean.add(s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s);
            }
            messages = clean.size() > MAX_MESSAGES ? clean.subList(0, MAX_MESSAGES) : clean;
            idx = 0;
        }
        if (body.has("intervalSec")) {
            int v = body.get("intervalSec").getAsInt();
            if (v < MIN_INTERVAL) {
                o.addProperty("success", false);
                o.addProperty("error", "间隔不能小于 " + MIN_INTERVAL + " 秒");
                return o;
            }
            intervalSec = v;
        }
        if (body.has("jitterSec")) jitterSec = Math.max(0, body.get("jitterSec").getAsInt());
        if (body.has("rotate")) rotate = body.get("rotate").getAsBoolean();
        if (body.has("letterSuffix")) letterSuffix = body.get("letterSuffix").getAsBoolean();
        if (body.has("enabled")) {
            boolean want = body.get("enabled").getAsBoolean();
            if (want && messages.isEmpty()) {
                o.addProperty("success", false);
                o.addProperty("error", "消息列表是空的，先加一条再开");
                return o;
            }
            enabled = want;
            if (want) nextAt = System.currentTimeMillis() + nextInterval();
            else nextAt = 0;
        }
        save();
        JsonObject st = state();
        st.addProperty("success", true);
        return st;
    }

    // ------------------------------------------------------------------ 发送

    private int nextInterval() {
        int base = Math.max(MIN_INTERVAL, intervalSec);
        if (jitterSec > 0) base += rnd.nextInt(jitterSec * 2 + 1) - jitterSec;
        return Math.max(MIN_INTERVAL, base);
    }

    private String withSuffix(String text) {
        if (!letterSuffix) return text;
        String pick;
        int guard = 0;
        do {
            pick = String.valueOf((char) ('a' + rnd.nextInt(26)));
            guard++;
        } while (pick.equals(lastSuffix) && guard < 8);
        lastSuffix = pick;
        String base = text.length() > MAX_TEXT - 1 ? text.substring(0, MAX_TEXT - 1) : text;
        return base + pick;
    }

    public synchronized JsonObject sendNow(Integer index) {
        JsonObject o;
        if (messages.isEmpty()) {
            o = new JsonObject();
            o.addProperty("success", false);
            o.addProperty("message", "消息列表是空的");
            return o;
        }
        int i;
        if (index != null) {
            i = Math.floorMod(index, messages.size());
        } else if (rotate) {
            i = Math.floorMod(idx, messages.size());
        } else {
            i = 0;
        }
        String text = withSuffix(messages.get(i));
        List<String> out = Probe.command("sendMessage " + text);
        boolean ok = out != null;
        lastText = text;
        lastSent = System.currentTimeMillis();
        if (ok) {
            sentCount++;
            if (rotate) idx = (i + 1) % messages.size();
        }
        save();
        o = state();
        o.addProperty("success", ok);
        o.addProperty("message", ok ? "已发送" : "面板命令失败");
        return o;
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(1000);
                boolean due;
                synchronized (this) {
                    if (!enabled || messages.isEmpty()) continue;
                    long now = System.currentTimeMillis();
                    if (nextAt <= 0) {
                        nextAt = now + nextInterval();
                        save();
                        continue;
                    }
                    due = now >= nextAt;
                }
                if (!due) continue;
                if (!online()) {
                    synchronized (this) {
                        nextAt = System.currentTimeMillis() + 30000;
                        save();
                    }
                    continue;
                }
                sendNow(null);
                synchronized (this) {
                    nextAt = System.currentTimeMillis() + nextInterval();
                    save();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean online() {
        try {
            java.util.Map<String, String> st = Probe.status();
            return "Online".equalsIgnoreCase(st.getOrDefault("status", ""));
        } catch (Throwable t) {
            return false;
        }
    }
}
