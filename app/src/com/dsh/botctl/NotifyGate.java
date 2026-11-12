package com.dsh.botctl;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知去重闸门。
 *
 * 规则（按用户要求）：**只看被检测玩家自己的坐标有没有变**，与机器人位置无关。
 *
 * - 第一次见到某玩家 -> 提醒
 * - 之后坐标和上次提醒时不同 -> 再提醒
 * - 坐标完全相同（站着不动）-> 不提醒
 * - 他离开过又回到同一坐标 -> 提醒一次（"回来了"），之后又静止就不再提醒
 * - 玩家离开视野本身不发通知（那不是坐标变动）
 *
 * 单独成类是因为这段最容易错：早先版本用「机器人与玩家的距离」做判断，
 * 机器人一移动距离就变，于是同一个站着不动的玩家被反复提醒。
 */
public class NotifyGate {

    /** 一个玩家的最近一次提醒状态。 */
    private static class Entry {
        int x, y, z;
        boolean hasCoords;   // 日志里是否带了坐标
        boolean left;        // 上次提醒之后是否离开过视野

        Entry(Nearby.Event ev) {
            hasCoords = ev.hasCoords();
            if (hasCoords) {
                x = ev.x;
                y = ev.y;
                z = ev.z;
            }
            left = false;
        }

        boolean sameCoords(Nearby.Event ev) {
            return hasCoords && ev.hasCoords() && x == ev.x && y == ev.y && z == ev.z;
        }

        void update(Nearby.Event ev) {
            if (ev.hasCoords()) {
                x = ev.x;
                y = ev.y;
                z = ev.z;
                hasCoords = true;
            }
            left = false;
        }
    }

    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    public void clear() {
        entries.clear();
    }

    public int tracked() {
        return entries.size();
    }

    /**
     * @return true 表示这条事件应该发通知（内部已更新记录）
     */
    public boolean shouldNotify(Nearby.Event ev) {
        if (ev == null) return false;

        // 离开视野 / 下线：只记一个标记，不打扰用户
        if (ev.type == Nearby.LEAVE || ev.type == Nearby.LOGOUT) {
            Entry e = entries.get(ev.name);
            if (e != null) e.left = true;
            return false;
        }
        if (ev.type != Nearby.ENTER) return false;

        Entry prev = entries.get(ev.name);
        if (prev == null) {
            entries.put(ev.name, new Entry(ev));
            return true;                       // 第一次见到
        }

        boolean evHas = ev.hasCoords();
        boolean pvHas = prev.hasCoords;

        if (evHas && pvHas) {
            if (prev.sameCoords(ev)) {
                if (prev.left) {               // 离开过又回到原位 -> 值得说一声
                    prev.left = false;
                    return true;
                }
                return false;                  // 原地不动，不打扰
            }
            prev.update(ev);                   // 坐标确实变了
            return true;
        }
        if (evHas && !pvHas) {
            prev.update(ev);                   // 这次终于带上坐标了
            return true;
        }
        // 这条事件没有坐标（模块没配坐标行）：无法判断是否移动，
        // 只在"他离开过又回来"时提醒一次，否则一直闭嘴——早先版本在这里
        // 每来一条都提醒，是反复弹通知的根源之一。
        if (prev.left) {
            prev.left = false;
            return true;
        }
        return false;
    }
}
