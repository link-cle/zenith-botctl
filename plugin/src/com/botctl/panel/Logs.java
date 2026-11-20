package com.botctl.panel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 日志捕获：把自己挂成 logback appender，收 ZenithProxy 的所有日志。
 *
 * 为什么这么做：插件 API 没有日志订阅接口，而 WebUI 当年也是这么干的
 * （继承 UnsynchronizedAppenderBase<ILoggingEvent>，见 wxh1024 的实现思路）。
 * 这里换了独立实现：环形缓冲 + 每个 SSE 客户端一条队列，慢客户端不会拖住写日志。
 */
public final class Logs extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** 最多保留多少条历史，供新连上的客户端回放。 */
    private static final int MAX_BUFFER = 800;

    private static final Deque<JsonObject> BUFFER = new ArrayDeque<>(MAX_BUFFER);
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    private static final List<Client> CLIENTS = new CopyOnWriteArrayList<>();

    /** 一个 SSE 客户端。 */
    public static final class Client {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(500);
    }

    public static Client subscribe() {
        Client c = new Client();
        CLIENTS.add(c);
        // 回放历史，避免刚连上时是一片空白
        synchronized (BUFFER) {
            for (JsonObject o : BUFFER) c.queue.offer(o.toString());
        }
        return c;
    }

    public static void unsubscribe(Client c) {
        CLIENTS.remove(c);
    }

    @Override
    protected void append(ILoggingEvent ev) {
        try {
            // 注意：这里绝对不能打日志，否则会递归
            JsonObject o = new JsonObject();
            o.addProperty("level", ev.getLevel() == null ? "INFO" : ev.getLevel().toString());
            o.addProperty("logger", ev.getLoggerName() == null ? "" : shortLogger(ev.getLoggerName()));
            o.addProperty("message", render(ev));
            o.addProperty("timestamp", ev.getTimeStamp());
            o.addProperty("seq", SEQ.incrementAndGet());

            synchronized (BUFFER) {
                BUFFER.addLast(o);
                while (BUFFER.size() > MAX_BUFFER) BUFFER.removeFirst();
            }
            String line = o.toString();
            for (Client c : CLIENTS) {
                if (!c.queue.offer(line)) {
                    c.queue.poll();          // 丢弃最旧的，别让慢客户端吃内存
                    c.queue.offer(line);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String render(ILoggingEvent ev) {
        String msg = ev.getFormattedMessage();
        if (msg == null) msg = "";
        IThrowableProxy tp = ev.getThrowableProxy();
        if (tp != null) {
            msg = msg + "\n" + ThrowableProxyUtil.asString(tp);
        }
        return msg;
    }

    /** 把 "com.zenith.feature.whitelist.PlayerListsManager" 缩短成 "PlayerListsManager"。 */
    private static String shortLogger(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    /** 当前缓冲条数（给 /api/health 看）。 */
    public static int size() {
        synchronized (BUFFER) {
            return BUFFER.size();
        }
    }

    /** 有没有至少 INFO 级别的过滤（不用严格，只是给调度用）。 */
    public static List<JsonObject> snapshot(int max) {
        List<JsonObject> out = new ArrayList<>();
        synchronized (BUFFER) {
            int skip = Math.max(0, BUFFER.size() - max);
            int i = 0;
            for (JsonObject o : BUFFER) {
                if (i++ >= skip) out.add(o);
            }
        }
        return out;
    }

    /** 当前序号；配合 since() 取"从此刻起新增的日志"。 */
    public static long mark() {
        return SEQ.get();
    }

    /** 取序号 > mark 的日志（用于抓某条命令的输出）。 */
    public static List<JsonObject> since(long mark) {
        List<JsonObject> out = new ArrayList<>();
        synchronized (BUFFER) {
            for (JsonObject o : BUFFER) {
                if (o.has("seq") && o.get("seq").getAsLong() > mark) out.add(o);
            }
        }
        return out;
    }

    static boolean isError(String level) {
        return "ERROR".equals(level) || Level.ERROR.toString().equals(level);
    }
}
