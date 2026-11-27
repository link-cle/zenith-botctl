package com.botctl.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.Globals;
import com.zenith.Proxy;
import com.zenith.cache.data.PlayerCache;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * 自动喝不祥之瓶（Ominous Bottle）。
 *
 * 为什么要认两个物品 id：
 *   mc.example.com 并没有用原版 ominous_bottle(1384)，而是用 honey_bottle(1277) + 自定义组件
 *   拼出来的"不祥之瓶"（物品提示里能看到 minecraft:honey_bottle 这一行）。
 *   所以默认两个 id 都认，哪个在手边就喝哪个。
 *
 * 喝法（和 SW 插件的 RaidAssist 一样）：主手临时切槽 → 发 useItem → 喝完切回原槽。
 *   - 不占副手（副手留给图腾保命）
 *   - 用 ServerboundSetCarriedItemPacket 切槽，并同步本地缓存
 *   - 用 ServerboundUseItemPacket 触发使用（服务端自己计时 32 tick 完成饮用）
 */
public final class Drink {

    /** 原版不祥之瓶 */
    private static final int ID_OMINOUS = 1384;
    /** mc.example.com 实际用的：蜂蜜瓶 + 组件 */
    private static final int ID_HONEY = 1277;

    private static Drink INSTANCE;

    private final Path file;
    private final Random rnd = new Random();

    private boolean enabled = false;
    private int seconds = 120;
    private int jitter = 15;
    private int useOminous = 1;   // 1=认原版不祥之瓶
    private int useHoney = 1;     // 1=认蜂蜜瓶(mc.example.com 那种)

    private long nextAt = 0;
    private long lastDrink = 0;
    private int drinkCount = 0;
    private String lastResult = "";

    private Drink(Path file) {
        this.file = file;
        load();
    }

    public static synchronized Drink get() {
        return INSTANCE;
    }

    public static synchronized void init(Path installDir) {
        if (INSTANCE == null) INSTANCE = new Drink(installDir.resolve("drink.json"));
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "botctl-drink");
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
            if (o.has("seconds")) seconds = Math.max(10, o.get("seconds").getAsInt());
            if (o.has("jitter")) jitter = Math.max(0, o.get("jitter").getAsInt());
            if (o.has("useOminous")) useOminous = o.get("useOminous").getAsInt();
            if (o.has("useHoney")) useHoney = o.get("useHoney").getAsInt();
            drinkCount = o.has("drinkCount") ? o.get("drinkCount").getAsInt() : 0;
            nextAt = o.has("nextAt") ? o.get("nextAt").getAsLong() : 0;
        } catch (Throwable ignored) {
        }
    }

    private synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("seconds", seconds);
            o.addProperty("jitter", jitter);
            o.addProperty("useOminous", useOminous);
            o.addProperty("useHoney", useHoney);
            o.addProperty("drinkCount", drinkCount);
            o.addProperty("nextAt", nextAt);
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
        o.addProperty("seconds", seconds);
        o.addProperty("jitter", jitter);
        o.addProperty("useOminous", useOminous == 1);
        o.addProperty("useHoney", useHoney == 1);
        o.addProperty("drinkCount", drinkCount);
        o.addProperty("lastDrink", lastDrink / 1000.0);
        o.addProperty("lastResult", lastResult);
        o.addProperty("nextAt", nextAt / 1000.0);
        o.addProperty("now", System.currentTimeMillis() / 1000.0);
        o.addProperty("bottlesHotbar", countBottles());
        o.addProperty("heldSlot", heldSlot());
        return o;
    }

    public synchronized JsonObject set(JsonObject body) {
        if (body.has("enabled")) {
            boolean want = body.get("enabled").getAsBoolean();
            enabled = want;
            if (want) nextAt = System.currentTimeMillis() + 3000;   // 开启后 3 秒先来一次
        }
        if (body.has("seconds")) seconds = Math.max(10, body.get("seconds").getAsInt());
        if (body.has("jitter")) jitter = Math.max(0, body.get("jitter").getAsInt());
        if (body.has("useOminous")) useOminous = body.get("useOminous").getAsBoolean() ? 1 : 0;
        if (body.has("useHoney")) useHoney = body.get("useHoney").getAsBoolean() ? 1 : 0;
        save();
        return state();
    }

    /** App 上的「立即喝一瓶」按钮。 */
    public synchronized JsonObject drinkNow() {
        boolean ok = doDrink();
        JsonObject o = state();
        o.addProperty("success", ok);
        return o;
    }

    // ------------------------------------------------------------------ 主循环

    private void loop() {
        while (true) {
            try {
                Thread.sleep(1000);
                if (!enabled) continue;
                if (System.currentTimeMillis() < nextAt) continue;
                doDrink();
                nextAt = System.currentTimeMillis() + nextInterval();
                save();
            } catch (Throwable ignored) {
            }
        }
    }

    private int nextInterval() {
        int base = Math.max(10, seconds);
        if (jitter > 0) base += rnd.nextInt(jitter * 2 + 1) - jitter;
        return Math.max(10, base);
    }

    // ------------------------------------------------------------------ 喝

    private boolean doDrink() {
        int prevIdx = -1;
        int idx = -1;
        try {
            Proxy proxy = Proxy.getInstance();
            if (proxy == null || proxy.getClient() == null || !proxy.getClient().isConnected()) {
                lastResult = "机器人未连接";
                return false;
            }
            int slot = findBottleSlot();          // 绝对槽位 36..44
            if (slot < 0) {
                lastResult = "快捷栏里没有不祥之瓶";
                return false;
            }
            idx = slot - 36;                       // 快捷栏序号 0..8
            prevIdx = heldSlot();                  // 也是 0..8
            if (idx != prevIdx) {
                switchHeld(idx);
                Thread.sleep(300);
            }

            // 喝法：用 ZenithProxy 自带的 click 命令"使用手上物品"。
            // 实测：click right useItem mainHand -> "Using Item | Hand: main_hand | Item: honey_bottle"
            // 而且物品计数真的 -1（手动发包那条路服务端不认，已废弃）。
            List<String> out = Probe.command("click right useItem mainHand");
            String joined = String.join(" ", out).toLowerCase(java.util.Locale.ROOT);
            if (!joined.contains("using item")) {
                lastResult = "click 未生效: "
                        + (joined.isEmpty() ? "无输出" : joined.substring(0, Math.min(60, joined.length())));
                if (idx != prevIdx) switchHeld(prevIdx);
                return false;
            }
            Thread.sleep(2000);                    // 等服务端完成饮用（约 32 tick）

            drinkCount++;
            lastDrink = System.currentTimeMillis();
            lastResult = "已喝（快捷栏第 " + (idx + 1) + " 格）";
            Globals.DEFAULT_LOG.info("[BotCtl] 自动喝不祥之瓶，累计 {} 次", drinkCount);
            return true;
        } catch (Throwable t) {
            lastResult = "喝失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
            return false;
        } finally {
            if (idx >= 0 && prevIdx >= 0 && idx != prevIdx) {
                try {
                    switchHeld(prevIdx);
                } catch (Throwable ignored) {
                }
            }
        }
    }


    private void switchHeld(int slot) {
        try {
            Proxy.getInstance().getClient().send(new ServerboundSetCarriedItemPacket(slot));
            Globals.CACHE.getPlayerCache().setHeldItemSlot(slot);
        } catch (Throwable ignored) {
        }
    }

    private int heldSlot() {
        try {
            return Globals.CACHE.getPlayerCache().getHeldItemSlot();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 在快捷栏（槽位 36..44）里找目标物品；优先当前手持。 */
    private int findBottleSlot() {
        try {
            List<ItemStack> inv = Globals.CACHE.getPlayerCache().getPlayerInventory();
            if (inv == null) return -1;
            int held = heldSlot();
            int fallback = -1;
            for (int slot = 36; slot <= 44; slot++) {
                if (slot >= inv.size()) break;
                ItemStack st = inv.get(slot);
                if (st == null || st.getId() <= 0) continue;
                if (!isTarget(st)) continue;
                if (slot == 36 + held) return slot;  // 手上就是，直接用
                if (fallback < 0) fallback = slot;
            }
            return fallback;
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean isTarget(ItemStack st) {
        int id = st.getId();
        if (useOminous == 1 && id == ID_OMINOUS) return true;
        if (useHoney == 1 && id == ID_HONEY) return true;
        // 兜底：按注册表名字匹配（版本不同 id 会变）
        try {
            Object data = ItemRegistry.REGISTRY.get(id);
            if (data != null) {
                String name = String.valueOf(data.getClass().getMethod("name").invoke(data));
                if (name.contains("ominous") || name.contains("honey_bottle")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int countBottles() {
        int n = 0;
        try {
            List<ItemStack> inv = Globals.CACHE.getPlayerCache().getPlayerInventory();
            if (inv == null) return 0;
            for (int slot = 36; slot <= 44 && slot < inv.size(); slot++) {
                ItemStack st = inv.get(slot);
                if (st != null && st.getId() > 0 && isTarget(st)) n += Math.max(1, st.getAmount());
            }
        } catch (Throwable ignored) {
        }
        return n;
    }
}
