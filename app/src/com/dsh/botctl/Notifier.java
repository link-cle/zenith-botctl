package com.dsh.botctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知渠道与两种通知（附近玩家提醒 / 后台监控常驻）。 */
public class Notifier {

    public static final String CH_NEARBY = "nearby";     // 附近玩家
    public static final String CH_WATCH = "watch";       // 后台监控常驻
    public static final int ID_WATCH = 1001;

    /** 幂等创建渠道；Android 8 以下不需要。 */
    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel nearby = new NotificationChannel(
                CH_NEARBY, "附近玩家", NotificationManager.IMPORTANCE_HIGH);
        nearby.setDescription("有玩家进入视野时提醒");
        nearby.enableVibration(true);
        nm.createNotificationChannel(nearby);

        NotificationChannel watch = new NotificationChannel(
                CH_WATCH, "后台监控", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("保持监听日志以推送附近玩家提醒");
        watch.setShowBadge(false);
        nm.createNotificationChannel(watch);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, flags);
    }

    private static int smallIcon() {
        // 自己的白色小图标（资源由 aapt2 生成 R 类提供）
        return R.drawable.ic_stat;
    }

    /**
     * 附近玩家通知。
     *
     * 标题是玩家名，正文只有玩家坐标「坐标(x y z)」——距离已按用户要求移除，
     * 坐标直接取自附近玩家日志里那一条的内容。
     */
    public static void nearby(Context c, String name, String coordsText) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String sb = "坐标" + coordsText;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH_NEARBY);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        b.setSmallIcon(smallIcon())
                .setContentTitle(name)
                .setContentText(sb)
                .setStyle(new Notification.BigTextStyle().bigText(sb))
                .setAutoCancel(true)
                .setContentIntent(openApp(c));

        String tag = "nearby-" + name;
        nm.notify(tag, (int) (System.currentTimeMillis() & 0xFFFFFF), b.build());
    }

    /** 前台服务要求的常驻通知。 */
    public static Notification watch(Context c, String text) {
        ensureChannels(c);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH_WATCH);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(smallIcon())
                .setContentTitle("BotCtl 监控中")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp(c));
        return b.build();
    }
}
