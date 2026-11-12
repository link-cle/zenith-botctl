package com.dsh.botctl;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;


/**
 * 后台监控服务：保持一条日志流（SSE），有人进入视野就发系统通知。
 *
 * 为什么要前台服务：Android 在应用切到后台后会冻结/回收普通进程，日志流会断，
 * 通知就永远等不到。前台服务带一条常驻通知，系统才允许它长期活着。
 *
 * 距离怎么算：dashboard 里没有坐标字段（只有 health/food），所以必须用
 * `status` 命令的 `Coordinates: ||[x, y, z]||`。之前误用 dashboard 导致读不到
 * 坐标、退回 [0,0,0]，算出来的距离是从原点起的，完全不对——这里改掉了。
 *
 * 去重：同一个玩家只在「第一次进入视野」和「位置相比上次通知移动超过 10 格」时
 * 才提醒。ZenithProxy 会反复输出 enter 事件，不做去重就会一直弹。
 */
public class MonitorService extends Service {

    private static final String TAG = "BotCtlMonitor";
    public static final String PREFS_NAME = "botctl4";
    public static final String PREF_ENABLED = "nearbyNotify";

    /** 机器人坐标缓存时间（毫秒）。 */
    private static final long POS_TTL_MS = 20_000L;

    private volatile boolean running;
    private Thread worker;
    private int notifiedCount;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.ensureChannels(this);
        try {
            startForeground(Notifier.ID_WATCH, Notifier.watch(this, "正在监听附近玩家…"));
        } catch (Exception e) {
            Log.w(TAG, "startForeground 失败: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null) {
            running = true;
            worker = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            }, "nearby-monitor");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        worker = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 主循环

    private void loop() {
        final Nearby nearby = new Nearby();
        final NotifyGate gate = new NotifyGate();
        // 机器人坐标缓存
        final int[][] botPos = new int[1][];
        final long[] botPosAt = new long[]{0L};

        while (running) {
            try {
                SharedPreferences p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String rawHost = p.getString("host", "");
                String cookie = p.getString("cookie", "");
                String user = p.getString("user", "admin");
                String pass = p.getString("pass", "");
                if (rawHost.length() == 0) {
                    idle(30);
                    continue;
                }
                String[] hp = Api.splitHostPort(rawHost, 8080);
                final Api api = new Api(hp[0], Integer.parseInt(hp[1]));
                if (cookie != null && cookie.length() > 0) api.setCookie(cookie);
                if (!api.hasCookie()) {
                    if (pass == null || pass.length() == 0) {
                        idle(30);
                        continue;
                    }
                    api.login(user, pass);
                    String ck = api.cookie();
                    if (ck != null && ck.length() > 0) p.edit().putString("cookie", ck).apply();
                }

                boolean[] run = new boolean[]{true};
                api.streamLogs(run, new Api.LogSink() {
                    public void onLog(JSONObject entry) {
                        String msg = entry.optString("message", "");
                        Nearby.Event ev = nearby.feed(msg);
                        if (ev == null || ev.type == Nearby.FRAGMENT) return;
                        if (ev.type != Nearby.ENTER && ev.type != Nearby.LOGOUT) return;

                        // 拿机器人坐标（缓存 20 秒，避免每条事件都发命令）
                        long now = System.currentTimeMillis();
                        if (botPos[0] == null || now - botPosAt[0] > POS_TTL_MS) {
                            int[] pos = readBotPos(api);
                            if (pos != null) {
                                botPos[0] = pos;
                                botPosAt[0] = now;
                            }
                        }

                        int dist = -1;
                        if (botPos[0] != null) {
                            dist = Nearby.distance(ev, botPos[0][0], botPos[0][1], botPos[0][2]);
                        }

                        // 去重：同一玩家只在首次进入、或位移超过 10 格时才提醒
                        if (gate.shouldNotify(ev)) {
                            notifyOne(ev, dist);
                        }
                    }

                    public void onError(String m) {
                        Log.w(TAG, "日志流断开: " + m);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "监控循环异常: " + e);
            }
            idle(3);
        }
    }

    private void notifyOne(Nearby.Event ev, int dist) {
        notifiedCount++;
        Notifier.nearby(this, ev.name, dist, ev.coordsText(), ev.type == Nearby.LOGOUT);
        try {
            startForeground(Notifier.ID_WATCH,
                    Notifier.watch(this, "监听中 · 已提醒 " + notifiedCount + " 次"));
        } catch (Exception ignored) {
        }
    }

    /** 用 status 命令取机器人坐标：Coordinates: ||[x, y, z]|| */
    private int[] readBotPos(Api api) {
        try {
            String st = api.commandText("status");
            Integer[] c = Api.parseCoords(st);
            if (c != null && c.length >= 3 && c[0] != null && c[1] != null && c[2] != null) {
                return new int[]{c[0], c[1], c[2]};
            }
        } catch (Exception e) {
            Log.w(TAG, "读坐标失败: " + e);
        }
        return null;
    }

    private void idle(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------ 启停

    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, MonitorService.class));
    }
}
