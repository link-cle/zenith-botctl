package com.botctl.panel;

import com.zenith.Globals;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.command.api.CommandSources;
import com.zenith.discord.Embed;
import com.zenith.feature.whitelist.PlayerEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 ZenithProxy 里取数据。
 *
 * 两条路：
 *  1) 命令 + 自定义 CommandSource —— 拿到和终端一模一样的文本（status / via / 任意命令）
 *  2) 直接读 Globals.CACHE —— 拿命令不给的东西（饥饿值、坐标精度）
 */
public final class Probe {

    private static final Pattern COORDS =
            Pattern.compile("Coordinates:\\s*\\|*\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]");
    private static final Pattern PROXY_IP = Pattern.compile("Proxy IP:\\s*(\\S+)");
    private static final Pattern SERVER = Pattern.compile("Server:\\s*(\\S+)");
    private static final Pattern PING = Pattern.compile("Ping:\\s*(\\d+)ms");
    private static final Pattern HEALTH = Pattern.compile("Health:\\s*([0-9.]+)");
    private static final Pattern DIM = Pattern.compile("Dimension:\\s*(\\S+)");
    private static final Pattern UPTIME = Pattern.compile("Uptime:\\s*(.+)");
    private static final Pattern ONLINE_DUR = Pattern.compile("Online Duration:\\s*(.+)");
    private static final Pattern STATUS = Pattern.compile("Status:\\s*(\\S+)");
    private static final Pattern HEADER = Pattern.compile("^ZenithProxy\\s+\\S+\\s+-\\s+(\\S+)$");
    private static final Pattern VIA_VER = Pattern.compile("Zenith To Server Version:\\s*(.+)");
    private static final Pattern QUEUE_PRI = Pattern.compile("Priority:\\s*(\\d+)");
    private static final Pattern QUEUE_REG = Pattern.compile("Regular:\\s*(\\d+)");

    /** 命令输出捕获：命令框架可能走 source.logMultiLine 或写入 context，两边都收。 */
    private static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public String name() {
            return "botctl-panel";
        }

        @Override
        public boolean validateAccountOwner(CommandContext ctx) {
            return true;
        }

        @Override
        public void logEmbed(CommandContext ctx, Embed embed) {
            if (embed == null) return;
            try {
                // ZenithProxy 的终端输出其实就是 Embed 的字段：
                //   field.name = "Status"，field.value = "Online"
                // 拼回 "name: value" 就得到和终端一模一样的文本。
                net.dv8tion.jda.api.entities.MessageEmbed jda = embed.toJDAEmbed();
                if (jda == null) return;
                if (jda.getTitle() != null) lines.add(jda.getTitle());
                if (jda.getDescription() != null) lines.add(jda.getDescription());
                for (net.dv8tion.jda.api.entities.MessageEmbed.Field f : jda.getFields()) {
                    String name = f.getName() == null ? "" : f.getName();
                    String value = f.getValue() == null ? "" : f.getValue();
                    lines.add(name.isEmpty() ? value : name + ": " + value);
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void logMultiLine(List<String> out) {
            if (out != null) lines.addAll(out);
        }
    }

    /**
     * 跑一条命令并取回输出。
     *
     * 为什么不是靠 CommandSource：ZenithProxy 的很多命令直接把结果打到**终端日志**
     * （logger=Terminal）里，不经过调用方给的 source，所以只实现 CommandSource
     * 会拿不到内容（实测 status 只有一行标题）。
     *
     * 这里改成：先记下日志序号 -> 执行命令 -> 收集新增的 Terminal 日志。
     * 与终端里看到的内容完全一致。
     */
    /** 把 Embed 渲染成 "字段名: 值" 的文本行（终端就是这么显示的）。 */
    static List<String> embedLines(Embed embed) {
        List<String> lines = new ArrayList<>();
        if (embed == null) return lines;
        try {
            net.dv8tion.jda.api.entities.MessageEmbed jda = embed.toJDAEmbed();
            if (jda == null) return lines;
            if (jda.getTitle() != null && !jda.getTitle().isEmpty()) lines.add(jda.getTitle());
            if (jda.getDescription() != null && !jda.getDescription().isEmpty()) {
                for (String s : jda.getDescription().split("\n")) {
                    if (!s.trim().isEmpty()) lines.add(s.trim());
                }
            }
            for (net.dv8tion.jda.api.entities.MessageEmbed.Field f : jda.getFields()) {
                String name = f.getName() == null ? "" : f.getName().trim();
                String value = f.getValue() == null ? "" : f.getValue().trim();
                if (name.isEmpty()) {
                    lines.add(value);
                } else if (value.isEmpty()) {
                    lines.add(name + ":");
                } else {
                    lines.add(name + ": " + value);
                }
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }
    /** 同一时间只允许一条命令在收集输出，避免并发交错。 */
    private static final Object CMD_LOCK = new Object();

    /**
     * 跑一条命令并取回输出。
     *
     * 为什么不是靠 CommandSource：ZenithProxy 的很多命令直接把结果打到**终端日志**
     * （logger=Terminal）里，不经过调用方给的 source，所以只实现 CommandSource
     * 会拿不到内容（实测 status 只有一行标题）。
     *
     * 实现：记下日志序号 -> 执行命令 -> 在固定时间窗内收集 Terminal 新增行。
     * 注意必须用**固定窗口**：这个服聊天很活跃，Terminal 日志一刻不停，
     * 如果"等安静再返回"就会永远等不到（曾经把面板线程池卡死）。
     */
    public static List<String> command(String input) {
        synchronized (CMD_LOCK) {
            List<String> ctxOut = new ArrayList<>();
            Embed embedOf = null;
            long mark = Logs.mark();
            try {
                Capture cap = new Capture();
                CommandContext ctx = CommandContext.create(input, cap);
                Globals.COMMAND.execute(ctx);
                embedOf = ctx.getEmbed();
                if (ctx.getMultiLineOutput() != null) ctxOut.addAll(ctx.getMultiLineOutput());
            } catch (Throwable t) {
                ctxOut.add("Error: " + t);
            }

            String echo = "> " + input;
            // 结构化输出优先（status/killAura 等都把结果放进 context 的 embed）
            List<String> fromEmbed = embedLines(embedOf);
            if (!fromEmbed.isEmpty()) return fromEmbed;

            long deadline = System.currentTimeMillis() + 400;
            List<String> collected = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                collected.clear();
                for (com.google.gson.JsonObject o : Logs.since(mark)) {
                    String logger = o.has("logger") ? o.get("logger").getAsString() : "";
                    if (!"Terminal".equals(logger)) continue;
                    String msg = o.has("message") ? o.get("message").getAsString() : "";
                    for (String line : msg.split("\n")) {
                        String s = line.trim();
                        if (s.isEmpty() || s.equals(echo) || s.equals(input)) continue;
                        collected.add(s);
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
            return collected.isEmpty() ? ctxOut : collected;
        }
    }
    /** 解析 `status` 命令输出。 */
    public static Map<String, String> status() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : command("status")) {
            if (line == null) continue;
            for (String part : line.split("\n")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                Matcher h = HEADER.matcher(s);
                if (h.find()) { m.put("username", h.group(1)); continue; }
                put(m, "status", STATUS, s);
                put(m, "ping", PING, s);
                put(m, "proxyIp", PROXY_IP, s);
                put(m, "server", SERVER, s);
                put(m, "health", HEALTH, s);
                put(m, "dimension", DIM, s);
                put(m, "uptime", UPTIME, s);
                put(m, "onlineDuration", ONLINE_DUR, s);
                Matcher c = COORDS.matcher(s);
                if (c.find()) {
                    m.put("x", c.group(1));
                    m.put("y", c.group(2));
                    m.put("z", c.group(3));
                }
                Matcher qp = QUEUE_PRI.matcher(s);
                if (qp.find()) m.put("queuePriority", qp.group(1));
                Matcher qr = QUEUE_REG.matcher(s);
                if (qr.find()) m.put("queueRegular", qr.group(1));
            }
        }
        return m;
    }

    private static void put(Map<String, String> m, String key, Pattern p, String line) {
        Matcher mm = p.matcher(line);
        if (mm.find() && !m.containsKey(key)) m.put(key, mm.group(1).trim());
    }

    /** via 版本 → "via 1.20.3-1.20.4"（关掉时返回空）。 */
    public static String viaVersion() {
        for (String line : command("via")) {
            if (line == null) continue;
            for (String part : line.split("\n")) {
                String s = part.trim();
                if (s.startsWith("Zenith To Server ViaVersion:") && s.endsWith("off")) return "";
                Matcher m = VIA_VER.matcher(s);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (v.toLowerCase().startsWith("auto")) {
                        // 形如 "Auto (1.20.3-1.20.4)"
                        int i = v.indexOf('(');
                        if (i >= 0) v = v.substring(i + 1).replace(")", "").trim();
                    }
                    return "via " + v;
                }
            }
        }
        return "";
    }

    /** 自身玩家的饥饿值 / 血量（命令不给饥饿值）。取不到返回 -1。 */
    public static double food() {
        try {
            Collection<EntityPlayer> players = Globals.CACHE.getEntityCache().getPlayers().values();
            for (EntityPlayer p : players) {
                if (p != null && p.isSelfPlayer()) return p.getFood();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public static double healthFromCache() {
        try {
            Collection<EntityPlayer> players = Globals.CACHE.getEntityCache().getPlayers().values();
            for (EntityPlayer p : players) {
                if (p != null && p.isSelfPlayer() && p.getHealth() != null) return p.getHealth();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ------------------------------------------------------------------ 白名单

    public static List<Map<String, String>> whitelistEntries() {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            for (PlayerEntry e : Globals.PLAYER_LISTS.getWhitelist().entries()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("username", String.valueOf(e.getUsername()));
                UUID id = e.getUuid();
                m.put("uuid", id == null ? "" : id.toString());
                out.add(m);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean whitelistAdd(String username) {
        try {
            return Globals.PLAYER_LISTS.getWhitelist().add(username).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean whitelistRemove(String username) {
        try {
            Globals.PLAYER_LISTS.getWhitelist().remove(username);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 config.json 读白名单开关和踢出提示（这两个只有配置文件里有）。 */
    public static String[] whitelistConfig() {
        String enabled = "false";
        String kick = "";
        try {
            String raw = new String(Files.readString(Globals.CONFIG_FILE.toPath(),
                    StandardCharsets.UTF_8));
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            com.google.gson.JsonObject server = root.getAsJsonObject("server");
            if (server != null && server.has("extra")) {
                com.google.gson.JsonObject extra = server.getAsJsonObject("extra");
                if (extra != null && extra.has("whitelist")) {
                    com.google.gson.JsonObject wl = extra.getAsJsonObject("whitelist");
                    if (wl != null) {
                        if (wl.has("enable")) enabled = wl.get("enable").getAsString();
                        if (wl.has("kickMessage")) kick = wl.get("kickMessage").getAsString();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{enabled, kick};
    }

    // ------------------------------------------------------------------ 系统指标

    private static long lastCpuTotal = -1, lastCpuIdle = -1;
    private static long lastNetRx = -1, lastNetTx = -1;
    private static long lastNetAt = 0;

    /** {cpuLoad(0..1), memPercent, rxBytesPerSec, txBytesPerSec} */
    public static double[] system() {
        double cpu = 0, mem = 0, rx = 0, tx = 0;
        try {
            List<String> stat = Files.readAllLines(Path.of("/proc/stat"));
            for (String line : stat) {
                if (line.startsWith("cpu ")) {
                    String[] f = line.trim().split("\\s+");
                    long total = 0;
                    for (int i = 1; i < f.length && i <= 8; i++) total += Long.parseLong(f[i]);
                    long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
                    if (lastCpuTotal > 0 && total > lastCpuTotal) {
                        long dt = total - lastCpuTotal;
                        long di = idle - lastCpuIdle;
                        if (dt > 0) cpu = Math.max(0, Math.min(1, (double) (dt - di) / dt));
                    }
                    lastCpuTotal = total;
                    lastCpuIdle = idle;
                    break;
                }
            }
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    long totalKb = Long.parseLong(line.replaceAll("\\D+", ""));
                    if (totalKb > 0) memTotalKb = totalKb;
                } else if (line.startsWith("MemAvailable:")) {
                    long availKb = Long.parseLong(line.replaceAll("\\D+", ""));
                    if (memTotalKb > 0) mem = 100.0 * (memTotalKb - availKb) / memTotalKb;
                }
            }
            long sumRx = 0, sumTx = 0;
            for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
                int i = line.indexOf(':');
                if (i < 0) continue;
                String[] f = line.substring(i + 1).trim().split("\\s+");
                if (f.length < 9) continue;
                sumRx += Long.parseLong(f[0]);
                sumTx += Long.parseLong(f[8]);
            }
            long now = System.currentTimeMillis();
            if (lastNetAt > 0 && now > lastNetAt) {
                double secs = (now - lastNetAt) / 1000.0;
                rx = Math.max(0, (sumRx - lastNetRx) / secs);
                tx = Math.max(0, (sumTx - lastNetTx) / secs);
            }
            lastNetRx = sumRx;
            lastNetTx = sumTx;
            lastNetAt = now;
        } catch (Throwable ignored) {
        }
        return new double[]{cpu, mem, rx, tx};
    }

    private static long memTotalKb = 0;
}
