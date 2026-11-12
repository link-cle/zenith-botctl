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
 * 凭据来源：SharedPreferences（与主界面同一份），不额外存密钥。
 */
public class MonitorService extends Service {

    private static final String TAG = "BotCtlMonitor";
    public static final String PREFS_NAME = "botctl4";
    public static final String PREF_ENABLED = "nearbyNotify";

    private volatile boolean running;
    private Thread worker;

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
        while (running) {
            Api api = null;
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
                api = new Api(hp[0], Integer.parseInt(hp[1]));
                if (cookie != null && cookie.length() > 0) api.setCookie(cookie);
                if (!api.hasCookie()) {
                    if (pass == null || pass.length() == 0) {
                        idle(30);
                        continue;
                    }
                    api.login(user, pass);
                    String ck = api.cookie();
                    if (ck != null && ck.length() > 0) {
                        p.edit().putString("cookie", ck).apply();
                    }
                }

                // 机器人坐标：每次重连读一次，之后靠日志里的坐标算距离
                int[] botPos = readBotPos(api);

                boolean[] run = new boolean[]{true};
                final int[] pos = botPos;
                api.streamLogs(run, new Api.LogSink() {
                    public void onLog(JSONObject entry) {
                        String msg = entry.optString("message", "");
                        Nearby.Event ev = nearby.feed(msg);
                        if (ev == null || ev.type == Nearby.FRAGMENT) return;
                        if (ev.type != Nearby.ENTER && ev.type != Nearby.LOGOUT) return;
                        int dist = Nearby.distance(ev, pos[0], pos[1], pos[2]);
                        Notifier.nearby(MonitorService.this, ev.name, dist,
                                ev.coordsText(), ev.type == Nearby.LOGOUT);
                    }

                    public void onError(String m) {
                        Log.w(TAG, "日志流断开: " + m);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "监控循环异常: " + e);
            }
            // 断线重连
            idle(3);
        }
    }

    /** 从 dashboard 拿机器人坐标，拿不到就给个不可能的哨兵值。 */
    private int[] readBotPos(Api api) {
        try {
            JSONObject d = api.dashboard();
            JSONObject p = d.optJSONObject("player");
            if (p != null) {
                JSONObject pos = p.optJSONObject("position");
                if (pos != null) {
                    return new int[]{pos.optInt("x", 0), pos.optInt("y", 0), pos.optInt("z", 0)};
                }
            }
        } catch (Exception ignored) {
        }
        return new int[]{0, 0, 0};
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
