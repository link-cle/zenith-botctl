package com.botctl.panel;

import ch.qos.logback.classic.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * BotCtl 面板插件入口。
 *
 * 目标：让手机 App 只依赖 ZenithProxy 本体 + 这一个插件，
 * 不再需要 ZenithProxy-WebUI（wxh1024），也不再需要额外 8081 服务。
 *
 * 一个 jar 里包含：认证 / dashboard / 命令 / SSE 日志 / 白名单 / 地图静态与删除 / 定时喊话。
 */
public final class BotCtlPlugin implements ZenithProxyPlugin {

    private static final String CONFIG_NAME = "config.json";

    private Panel panel;
    private Logs appender;
    private Path dataDir;

    @Override
    public void onLoad(PluginAPI api) {
        try {
            dataDir = Path.of("plugins", "botctl");
            Files.createDirectories(dataDir);
            JsonObject cfg = loadOrCreateConfig();

            // 1) 日志：挂成 logback appender
            attachAppender();

            // 2) 定时喊话
            Path installDir = Path.of(".").toAbsolutePath().normalize();
            Chat.init(installDir);
            Chat.get().start();
            Raid.init(installDir);
            Raid.get().start();

            // 3) HTTP 面板
            panel = new Panel(cfg, installDir);
            panel.start();

            api.getLogger().info("BotCtl 面板已启动：端口 {}（日志/命令/白名单/地图/喊话 全在这一个端口）",
                    cfg.has("port") ? cfg.get("port").getAsInt() : 8080);
        } catch (Throwable t) {
            api.getLogger().error("BotCtl 面板启动失败", t);
        }
    }

    private JsonObject loadOrCreateConfig() {
        Path file = dataDir.resolve(CONFIG_NAME);
        try {
            if (Files.exists(file)) {
                return JsonParser.parseString(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Throwable ignored) {
        }
        JsonObject cfg = new JsonObject();
        cfg.addProperty("port", 8080);
        cfg.addProperty("username", "admin");
        cfg.addProperty("password", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        cfg.addProperty("tokenExpiryMs", 86400000L);
        try {
            Files.write(file, cfg.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
        return cfg;
    }

    private void attachAppender() {
        try {
            appender = new Logs();
            appender.setContext((ch.qos.logback.classic.LoggerContext)
                    LoggerFactory.getILoggerFactory());
            appender.setName("botctl-panel");
            appender.start();
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.addAppender(appender);
        } catch (Throwable ignored) {
        }
    }
}
