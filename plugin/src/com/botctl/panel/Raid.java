package com.botctl.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.Globals;
import com.zenith.cache.data.bossbar.BossBar;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 袭击助手（Raid Assistant）。
 *
 * 来源参考：SW-meteor-addon 的 SWRaidAssist671——它在袭击期间按随机间隔（minTicks~maxTicks）
 * 自动喝药、开/关背包，用来"保持活跃 + 消耗药水"。客户端那套 UI 动作对无头机器人没有意义，
 * 所以这里移植的是同一件事在 bot 上的等价物：
 *
 *   袭击开始 -> 自动把 killAura 的目标切成袭击怪、开启自动进食与防挂机
 *   袭击结束 -> 全部还原成进袭前的状态
 *
 * 为什么用命令而不是直接调 API：killAura / autoEat / antiAfk 都是 ZenithProxy 自带模块，
 * 用它们自己的命令去开关，状态和界面完全一致，也不会和模块内部状态打架。
 */
public final class Raid {

    /** 袭击怪（1.21 的 raid 组成）。 */
    private static final String[] RAID_MOBS = {
            "pillager", "vindicator", "evoker", "ravager", "vex", "witch", "illusioner"
    };

    private static Raid INSTANCE;

    private final Path file;

    // ---- 配置（App 可改）
    private boolean enabled = false;
    private boolean autoFight = true;     // 打袭击怪
    private boolean autoEat = true;       // 开自动进食
    private boolean antiAfk = true;       // 开防挂机（对应 addon 的"保持活跃"）
    private int pollSeconds = 3;          // 检查间隔

    // ---- 运行时
    private volatile boolean active;      // 是否检测到袭击
    private volatile boolean simulated;   // 手动模拟中（跳过自动"袭击结束"判定）
    private String raidTitle = "";
    private long activeSince;
    private long lastCheck;
    private int triggerCount;

    // ---- 进袭前状态（用于还原）
    private boolean prevKillAura;
    private boolean prevTargetCustom;
    private boolean prevAutoEat;
    private boolean prevAntiAfk;
    private final List<String> addedTargets = new ArrayList<>();
    private final List<String> prevTargets = new ArrayList<>();

    private Raid(Path file) {
        this.file = file;
        load();
    }

    public static synchronized Raid get() {
        return INSTANCE;
    }

    public static synchronized void init(Path installDir) {
        if (INSTANCE == null) INSTANCE = new Raid(installDir.resolve("raidassist.json"));
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "botctl-raid");
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
            autoFight = !o.has("autoFight") || o.get("autoFight").getAsBoolean();
            autoEat = !o.has("autoEat") || o.get("autoEat").getAsBoolean();
            antiAfk = !o.has("antiAfk") || o.get("antiAfk").getAsBoolean();
            if (o.has("pollSeconds")) pollSeconds = Math.max(1, Math.min(30, o.get("pollSeconds").getAsInt()));
            triggerCount = o.has("triggerCount") ? o.get("triggerCount").getAsInt() : 0;
        } catch (Throwable ignored) {
        }
    }

    private synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("autoFight", autoFight);
            o.addProperty("autoEat", autoEat);
            o.addProperty("antiAfk", antiAfk);
            o.addProperty("pollSeconds", pollSeconds);
            o.addProperty("triggerCount", triggerCount);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 状态 / 配置

    public synchronized JsonObject state() {
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("enabled", enabled);
        o.addProperty("active", active);
        o.addProperty("raidTitle", raidTitle);
        o.addProperty("activeSeconds", active ? (System.currentTimeMillis() - activeSince) / 1000 : 0);
        o.addProperty("autoFight", autoFight);
        o.addProperty("autoEat", autoEat);
        o.addProperty("antiAfk", antiAfk);
        o.addProperty("pollSeconds", pollSeconds);
        o.addProperty("triggerCount", triggerCount);
        JsonArray added = new JsonArray();
        for (String s : addedTargets) added.add(s);
        o.add("addedTargets", added);
        o.addProperty("now", System.currentTimeMillis() / 1000.0);
        return o;
    }

    public synchronized JsonObject set(JsonObject body) {
        if (body.has("enabled")) {
            boolean want = body.get("enabled").getAsBoolean();
            if (want && !enabled) {
                enabled = true;
                configured(new JsonObject());
            } else if (!want && enabled) {
                enabled = false;
                if (active) endRaid("/api/raid/set 关闭");
            }
        }
        configured(body);
        save();
        return state();
    }

    private void configured(JsonObject body) {
        if (body.has("autoFight")) autoFight = body.get("autoFight").getAsBoolean();
        if (body.has("autoEat")) autoEat = body.get("autoEat").getAsBoolean();
        if (body.has("antiAfk")) antiAfk = body.get("antiAfk").getAsBoolean();
        if (body.has("pollSeconds")) {
            pollSeconds = Math.max(1, Math.min(30, body.get("pollSeconds").getAsInt()));
        }
    }

    /**
     * 手动立刻跑一次检测（App 上的「立即检测」按钮）。
     */
    public synchronized JsonObject checkNow() {
        check();
        return state();
    }

    /** 模拟袭击开始（用来验证"切目标/还原"这条链路，不需要真的等一场袭击）。 */
    public synchronized JsonObject simulateStart() {
        if (!active) {
            simulated = true;
            startRaid("SIMULATED 测试袭击");
        }
        return state();
    }

    /** 结束当前（含模拟的）袭击并还原。 */
    public synchronized JsonObject simulateEnd() {
        simulated = false;
        if (active) endRaid("手动结束");
        return state();
    }

    // ------------------------------------------------------------------ 主循环

    private void loop() {
        while (true) {
            try {
                Thread.sleep(Math.max(1, pollSeconds) * 1000L);
                if (!enabled) {
                    if (active) endRaid("助手已关闭");
                    continue;
                }
                check();
            } catch (Throwable ignored) {
            }
        }
    }

    private void check() {
        if (simulated) return;          // 模拟状态下不要自动结束
        String title = findRaidBar();
        boolean now = title != null;
        if (now && !active) startRaid(title);
        else if (!now && active) endRaid("袭击结束");
    }

    /** 读 Boss 栏，找标题里带 Raid / 袭击 的那条。 */
    private String findRaidBar() {
        try {
            for (BossBar bar : Globals.CACHE.getBossBarCache().getBossBars().values()) {
                if (bar == null || bar.getTitle() == null) continue;
                String text = LegacyComponentSerializer.legacySection().serialize(bar.getTitle());
                String plain = stripFormatting(text).toLowerCase(Locale.ROOT);
                if (plain.contains("raid") || plain.contains("袭击")) {
                    return stripFormatting(text).trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String stripFormatting(String s) {
        return s == null ? "" : s.replaceAll("\u00A7.", "").replaceAll("[\\x00-\\x1F]", "");
    }

    // ------------------------------------------------------------------ 进入 / 退出袭击

    private void startRaid(String title) {
        active = true;
        raidTitle = title;
        activeSince = System.currentTimeMillis();
        triggerCount++;
        Globals.DEFAULT_LOG.info("[BotCtl] 检测到袭击开始：{}，激活袭击助手", title);

        // 1) 记录进袭前状态
        prevKillAura = flag("KillAura");
        prevTargetCustom = flag("Target Custom");
        prevAutoEat = flag("AutoEat");
        prevAntiAfk = flag("AntiAfk");
        prevTargets.clear();
        prevTargets.addAll(currentTargets());
        addedTargets.clear();

        // 2) 打袭击怪
        if (autoFight) {
            Set<String> have = new LinkedHashSet<>(prevTargets);
            for (String mob : RAID_MOBS) {
                String up = mob.toUpperCase(Locale.ROOT);
                if (!have.contains(up)) {
                    Probe.command("killAura targetCustom add " + mob);
                    addedTargets.add(up);
                }
            }
            Probe.command("killAura targetCustom on");
            Probe.command("killAura on");
        }

        // 3) 自动进食 + 防挂机（对应 addon 的"喝药/保持活跃"）
        if (autoEat) Probe.command("autoEat on");
        if (antiAfk) Probe.command("antiAfk on");

        save();
    }

    private void endRaid(String reason) {
        active = false;
        simulated = false;
        raidTitle = "";
        Globals.DEFAULT_LOG.info("[BotCtl] 袭击助手退出（{}），还原状态", reason);

        // 还原 killAura：先删掉我们加的目标，再恢复开关
        for (String up : addedTargets) {
            Probe.command("killAura targetCustom del " + up.toLowerCase(Locale.ROOT));
        }
        addedTargets.clear();
        if (autoFight) {
            Probe.command("killAura targetCustom " + (prevTargetCustom ? "on" : "off"));
            Probe.command("killAura " + (prevKillAura ? "on" : "off"));
        }
        if (autoEat) Probe.command("autoEat " + (prevAutoEat ? "on" : "off"));
        if (antiAfk) Probe.command("antiAfk " + (prevAntiAfk ? "on" : "off"));
        save();
    }

    // ------------------------------------------------------------------ 读状态的小工具

    /** 从 killAura / 模块回显里读 "xxx: on/off"。 */
    private static boolean flag(String key) {
        String cmd;
        if ("KillAura".equals(key) || "Target Custom".equals(key)) cmd = "killAura";
        else if ("AutoEat".equals(key)) cmd = "autoEat";
        else if ("AntiAfk".equals(key)) cmd = "antiAfk";
        else cmd = key.toLowerCase(Locale.ROOT);
        try {
            List<String> out = Probe.command(cmd);
            for (String line : out) {
                for (String part : line.split("\n")) {
                    String s = part.trim();
                    if (s.startsWith(key + ":")) {
                        return s.substring(key.length() + 1).trim().toLowerCase(Locale.ROOT).startsWith("on");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<String> currentTargets() {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Probe.command("killAura")) {
                if (line == null) continue;
                for (String part : line.split("\n")) {
                    String s = part.trim();
                    if (s.startsWith("[") && s.endsWith("]")) {
                        for (String t : s.substring(1, s.length() - 1).split(",")) {
                            String v = t.trim();
                            if (!v.isEmpty()) out.add(v.toUpperCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
