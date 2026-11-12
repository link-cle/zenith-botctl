package com.dsh.botctl;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知去重闸门。
 *
 * 唯一判据：**被检测玩家自己的坐标有没有变**（与机器人位置无关）。
 *
 * - 第一次见到某玩家 -> 提醒
 * - 坐标和上次提醒时不同 -> 再提醒
 * - 坐标完全相同 -> 一律不提醒（哪怕他反复进出视野、反复触发 enter 事件）
 * - 离开/下线事件本身不提醒
 *
 * 为什么这么严格：实测该服务器上，同一个玩家在视野边缘会每 2 秒
 * enter/leave 一次（坐标一模一样），而且同一条事件会被 Module 和 Terminal
 * 两个 logger 各发一遍。早先版本因此每 2 秒弹两条通知。现在只要坐标没变就闭嘴。
 */
public class NotifyGate {

    /** 一个玩家的最近一次提醒坐标。 */
    private static class Entry {
        int x, y, z;
        boolean hasCoords;

        Entry(Nearby.Event ev) {
            hasCoords = ev.hasCoords();
            if (hasCoords) {
                x = ev.x;
                y = ev.y;
                z = ev.z;
            }
        }

        boolean sameCoords(int px, int py, int pz) {
            return hasCoords && x == px && y == py && z == pz;
        }

        void update(int px, int py, int pz) {
            x = px;
            y = py;
            z = pz;
            hasCoords = true;
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

        // 离开 / 下线：不打扰，也不改记录（坐标仍是上次提醒时的值）
        if (ev.type == Nearby.LEAVE || ev.type == Nearby.LOGOUT) return false;
        if (ev.type != Nearby.ENTER) return false;

        Entry prev = entries.get(ev.name);
        if (prev == null) {
            entries.put(ev.name, new Entry(ev));
            return true;                       // 第一次见到这个玩家
        }
        if (!ev.hasCoords()) {
            // 这条日志没带坐标，无从比较：不再提醒（避免同一事件刷两次）
            return false;
        }
        if (!prev.hasCoords) {
            prev.update(ev.x, ev.y, ev.z);     // 这次终于有坐标了
            return true;
        }
        if (prev.sameCoords(ev.x, ev.y, ev.z)) {
            return false;                      // 坐标没变 -> 闭嘴
        }
        prev.update(ev.x, ev.y, ev.z);         // 坐标确实变了
        return true;
    }
}
