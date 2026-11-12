package com.dsh.botctl;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知去重闸门。
 *
 * 需求：同一个玩家最多提醒一次；只有当他相对于「上次提醒时的位置」移动超过
 * 10 格，才允许再提醒一次。玩家下线时提醒一次并清空记录，之后再进视野算新的一次。
 *
 * 单独成类是刻意的：这段逻辑最容易出错（旧版就是对每条 enter 事件都发通知），
 * 抽出来就能用纯 Java 单测，不需要真机。
 */
public class NotifyGate {

    /** 位移阈值（方块 = 米）。 */
    public static final int MOVE_THRESHOLD = 10;

    private final Map<String, int[]> lastPos = new HashMap<String, int[]>();

    public void clear() {
        lastPos.clear();
    }

    public int tracked() {
        return lastPos.size();
    }

    /**
     * @return true 表示这条事件应该发通知（并且内部已记录本次位置）
     */
    public boolean shouldNotify(Nearby.Event ev) {
        if (ev == null) return false;

        // 下线：提醒过的人才提醒，然后清掉，方便他再进来时重新提醒
        if (ev.type == Nearby.LOGOUT) {
            return lastPos.remove(ev.name) != null;
        }
        if (ev.type != Nearby.ENTER) return false;

        int[] prev = lastPos.get(ev.name);
        if (prev == null) {
            lastPos.put(ev.name, posOf(ev));
            return true;                       // 第一次见到他
        }
        if (!ev.hasCoords()) {
            return false;                      // 没有坐标可比，按“没动”处理
        }
        double dx = ev.x - prev[0];
        double dy = ev.y - prev[1];
        double dz = ev.z - prev[2];
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moved > MOVE_THRESHOLD) {
            lastPos.put(ev.name, posOf(ev));
            return true;                       // 真的走开了，再提醒一次
        }
        return false;                          // 原地打转，不打扰
    }

    private static int[] posOf(Nearby.Event ev) {
        if (!ev.hasCoords()) return new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        return new int[]{ev.x, ev.y, ev.z};
    }
}
