package com.dsh.botctl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 附近玩家（VisualRange）消息解析。
 *
 * ZenithProxy 会把一条视野事件拆成多条日志（Player In Visual Range / Player Name /
 * 名字 / Player UUID / UUID / Coordinates / 坐标），本类把它们拼成一个结构化事件，
 * 同时供「日志页」和「通知服务」使用——UI 只负责展示，服务只负责推送。
 */
public class Nearby {

    public static final int NONE = -1;    // 不是视野消息
    public static final int FRAGMENT = 0; // 是碎片，被吞掉
    public static final int ENTER = 1;
    public static final int LEAVE = 2;
    public static final int LOGOUT = 3;

    /** 一次完整的视野事件。type==FRAGMENT 时其余字段无意义。 */
    public static class Event {
        public int type = NONE;
        public String name = "";
        public Integer x, y, z;

        public boolean hasCoords() {
            return x != null && y != null && z != null;
        }

        /** 坐标 (123 67 321) —— 按用户要求用空格分隔。 */
        public String coordsText() {
            if (!hasCoords()) return "(?, ?, ?)";
            return "(" + x + " " + y + " " + z + ")";
        }
    }

    private static final Pattern P_ENTER = Pattern.compile(
            "(?:\\[VisualRange\\]\\s*)?([A-Za-z0-9_|]+)\\s+entered visual range\\s*\\[([^\\]]+)\\]");
    private static final Pattern P_LEAVE = Pattern.compile(
            "(?:\\[VisualRange\\]\\s*)?([A-Za-z0-9_|]+)\\s+left visual range");
    private static final Pattern P_NAME = Pattern.compile("Player Name\\s*\\n?\\s*(\\S+)");
    private static final Pattern P_COORDS = Pattern.compile("Coordinates\\s*\\n?\\s*\\[([^\\]]+)\\]");
    private static final Pattern P_BRACKET = Pattern.compile(
            "\\[\\s*(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)\\s*,\\s*(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)\\s*,\\s*(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)\\s*\\]");
    private static final Pattern P_BARE = Pattern.compile(
            "(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)\\s*,\\s*(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)\\s*,\\s*(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)");

    private int reason = NONE;
    private String pendingName;
    private String expect;

    public void reset() {
        reason = NONE;
        pendingName = null;
        expect = null;
    }

    /**
     * @return null  -> 不是视野消息，按普通日志处理
     *         Event（type==FRAGMENT）-> 已被吞掉的碎片
     *         其它 Event -> 一条完整事件
     */
    public Event feed(String raw) {
        String m = raw == null ? "" : raw.trim();
        Event ev = new Event();

        // 模块单行日志
        Matcher en = P_ENTER.matcher(m);
        if (en.find()) {
            ev.type = ENTER;
            ev.name = en.group(1);
            parseInto(ev, en.group(2));
            return ev;
        }
        Matcher lv = P_LEAVE.matcher(m);
        if (lv.find()) {
            ev.type = LEAVE;
            ev.name = lv.group(1);
            return ev;
        }

        // 整块挤在一条消息里（logger=Terminal 的多行块）
        if (m.contains("Visual Range") && m.indexOf('\n') >= 0) {
            // 注意：离开视野的块也叫 "... Visual Range"，只排除 Logout 会把
            // "Player Left Visual Range" 误判成进入，从而反复通知。
            int reasonType;
            if (m.contains("Logout")) {
                reasonType = LOGOUT;
            } else if (m.contains("Left Visual Range")) {
                reasonType = LEAVE;
            } else {
                reasonType = ENTER;
            }
            Matcher nm = P_NAME.matcher(m);
            Matcher cd = P_COORDS.matcher(m);
            if (cd.find()) {
                ev.type = reasonType;
                ev.name = nm.find() ? nm.group(1) : "?";
                parseInto(ev, cd.group(1));
                return ev;
            }
        }

        // 逐条碎片
        if (m.equals("Player In Visual Range")) {
            reason = ENTER;
            pendingName = null;
            expect = null;
            ev.type = FRAGMENT;
            return ev;
        }
        if (m.equals("Player Logout In Visual Range")) {
            reason = LOGOUT;
            pendingName = null;
            expect = null;
            ev.type = FRAGMENT;
            return ev;
        }
        if (reason == NONE) return null;

        if (m.startsWith("Player Name")) {
            expect = "name";
            ev.type = FRAGMENT;
            return ev;
        }
        if (m.startsWith("Player UUID")) {
            expect = "uuid";
            ev.type = FRAGMENT;   // UUID 一律丢弃
            return ev;
        }
        if (m.startsWith("Coordinates")) {
            expect = "coords";
            ev.type = FRAGMENT;
            return ev;
        }
        if ("name".equals(expect)) {
            pendingName = m;
            expect = null;
            ev.type = FRAGMENT;
            return ev;
        }
        if ("uuid".equals(expect)) {
            expect = null;
            ev.type = FRAGMENT;
            return ev;
        }
        if ("coords".equals(expect)) {
            ev.type = reason;
            ev.name = pendingName == null ? "?" : pendingName;
            parseInto(ev, m);
            reset();
            return ev;
        }
        return null;
    }

    /**
     * 从 "245.7, 73.0, 201.1" / "[245.7, 73.0, 201.1]" / "[8440371.9, 70.0, 1.16E7]"
     * 里取整数坐标。
     *
     * 两个坑：
     * 1. Module 日志用**科学计数法**（1.1622633266436212E7），只认 [0-9.] 会把 Z 读成 1。
     * 2. 取整必须用 floor：同一事件 Terminal 日志给的是 8440371（截断），
     *    Module 给 8440371.9，用 round 会变成 8440372，两边对不上就永远"坐标变了"。
     */
    private static void parseInto(Event ev, String raw) {
        if (raw == null) return;
        String s = raw.trim();
        Matcher m = P_BRACKET.matcher(s);
        if (!m.find()) {
            m = P_BARE.matcher(s);
            if (!m.find()) return;
        }
        try {
            ev.x = (int) Math.floor(Double.parseDouble(m.group(1)));
            ev.y = (int) Math.floor(Double.parseDouble(m.group(2)));
            ev.z = (int) Math.floor(Double.parseDouble(m.group(3)));
        } catch (Exception ignored) {
        }
    }

    /** 三维直线距离（格），取整。缺坐标时返回 -1。 */
    public static int distance(Event ev, int bx, int by, int bz) {
        if (ev == null || !ev.hasCoords()) return -1;
        double dx = ev.x - bx;
        double dy = ev.y - by;
        double dz = ev.z - bz;
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /** 日志页显示的中文一行，格式与旧版保持一致。 */
    public static String describe(Event ev) {
        if (ev == null) return "";
        switch (ev.type) {
            case ENTER:
                return "有玩家进入视野 / 玩家名 / " + ev.name + " / 玩家坐标"
                        + (ev.hasCoords() ? "[" + ev.x + ", " + ev.y + ", " + ev.z + "]" : "[?]");
            case LEAVE:
                return "有玩家离开视野 / 玩家名 / " + ev.name;
            case LOGOUT:
                return "有玩家在视野内下线 / 玩家名 / " + ev.name
                        + (ev.hasCoords() ? " / 玩家坐标[" + ev.x + ", " + ev.y + ", " + ev.z + "]" : "");
            default:
                return "";
        }
    }
}
