package com.dsh.botctl;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BotCtl 4.0 - single-activity controller for a ZenithProxy bot via the WebUI plugin API.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "botctl4";
    private static final int DEF_PORT = 8080;
    private static final int MAP_ADMIN_PORT = 8081;   // map delete service
    // No server address and no password are baked in: the APK is meant to be shared,
    // so whoever installs it enters their own deployment details.
    private static final int MAX_LOG_LINES = 600;

    // log categories
    private static final int CAT_ALL = 0;
    private static final int CAT_MY_DEATH = 1;
    private static final int CAT_SRV_DEATH = 2;
    private static final int CAT_NEARBY = 3;
    private static final int CAT_CHAT = 4;
    private static final int CAT_SYS = 5;

    private static final String[] CAT_NAMES = {
            "全部", "个人死亡", "服务器死亡", "附近玩家", "聊天", "系统"
    };

    /** One rendered console line, kept structured so filters don't re-parse text. */
    private static final class LogLine {
        final String time;
        final int cat;
        final String text;

        LogLine(String time, int cat, String text) {
            this.time = time;
            this.cat = cat;
            this.text = text;
        }
    }

    private Api api;
    private boolean loggedIn = false;
    private volatile boolean alive = true;
    private boolean sseActive = false;
    private final boolean[] sseRun = new boolean[]{true};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // ---- status panel
    private TextView tvBadge, tvAcct, tvSrv, tvCoords, tvDim, tvOnline, tvPing, tvHealth, tvFood;
    private TextView tvQueue, tvSys, tvErr;
    private ProgressBar pbHp, pbFood;
    private PingChart chart;
    private Button btnUp, btnDown, btnRefresh;

    // ---- whitelist panel
    private LinearLayout wlBox;
    private EditText wlInput;
    private TextView wlMsg, wlHead;

    // ---- log panel
    private TextView logView;
    private ScrollView logScroll;
    private final List<LogLine> logLines = new ArrayList<LogLine>();
    private Button[] logBtns;
    private int logFilter = CAT_ALL;
    private String botUsername = "";
    private String currentServer = "";
    private boolean logDirty = false;
    private boolean followTail = true;   // auto-scroll to newest only when already at bottom
    private static final int RENDER_LINES = 200;   // rows actually pushed into the TextView
    private static final int MAX_LOG_CHARS = 60000;

    // ---- game account switcher
    private LinearLayout acctBox;
    private EditText acctName;
    private CheckBox acctOffline;
    private TextView acctMsg2;

    // ---- tools tab
    private TextView toolServer, toolOut, toolMsg;
    private EditText srvInput, pathX, pathZ, pathPlayer, pathBlock, chatInput, rawCmd, infoName;
    private ScrollView toolsScroll;
    private TextView viaState;
    private final java.util.List<Button> viaVersionBtns = new java.util.ArrayList<Button>();
    private final java.util.List<String> viaVersionLabels = new java.util.ArrayList<String>();
    private static final String[] VIA_VERSIONS = {
            "auto", "1.21.4", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.20",
            "1.19.4", "1.19.2", "1.18.2", "1.17.1", "1.16.5", "1.15.2", "1.14.4", "1.12.2", "1.8.9",
    };
    private LinearLayout srvBox;
    private final java.util.Map<String, TextView> modStates = new java.util.LinkedHashMap<String, TextView>();
    private static final String[][] MODULES = {
            {"防挂机 AntiAFK", "antiAFK"},
            {"自动进食 AutoEat", "autoEat"},
            {"自动图腾 AutoTotem", "autoTotem"},
            {"自动穿甲 AutoArmor", "autoArmor"},
            {"自动修补 AutoMend", "autoMend"},
            {"防踢出 AntiKick", "antiKick"},
            {"危险自动下线 AutoDisconnect", "autoDisconnect"},
            {"自动回复 AutoReply", "autoReply"},
            {"自动攻击 KillAura", "killAura"},
    };

    // ---- map tab
    private LinearLayout mapGrid;
    private TextView mapMsg;
    private EditText mapDist;
    private ImageView mapBig;
    private LinearLayout mapBigWrap;

    // ---- privacy
    private boolean hideSensitive = false;
    private CheckBox cbHide;

    // ---- account panel
    private EditText edHost, edPort, edUser, edPass;
    private TextView tvAcctMsg, tvDevice;

    // ---- shell
    private FrameLayout panelHost;
    private View[] panels;
    private Button[] tabs;

    private static final Pattern P_LINK = Pattern.compile("(https?://\\S*?microsoft\\.com/link\\S*)");
    private static final Pattern P_CODE = Pattern.compile("code[:\\s]+([A-Z0-9]{6,12})");
    private static final Pattern P_DEATH = Pattern.compile(
            "(drowned|died|was slain|was killed|was shot|blew up|fell from|hit the ground|"
                    + "burned|went up in flames|starved|suffocated|withered|was fireballed|"
                    + "was pummeled|was pricked|was impaled|froze|was struck by lightning|"
                    + "experienced kinetic energy|tried to swim in lava)",
            Pattern.CASE_INSENSITIVE);

    // ===================================================================== life

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        buildUi();
        loadPrefs();

        // Nothing is baked into this build: the APK is shareable, so the person who
        // installs it fills in their own panel address, user and password.
        if (edHost.getText().toString().trim().length() == 0) {
            selectTab(6);   // 「账号」页
            setAcctMsg("首次使用：请填写面板地址、用户名和密码，然后点「保存并连接」");
        } else {
            connectAsync();
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        sseRun[0] = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ======================================================================= ui

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(Color.parseColor("#757575"));
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private TextView value(String text, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.parseColor("#212121"));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView bigStat(String caption) {
        TextView t = new TextView(this);
        t.setText(caption);
        t.setTextSize(15f);
        t.setTextColor(Color.parseColor("#424242"));
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        // FrameLayout wrapper so the password gate can cover the whole screen
        FrameLayout rootWrap = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Color.parseColor("#1565C0"));
        head.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvBadge = new TextView(this);
        tvBadge.setText("● 未连接");
        tvBadge.setTextSize(16f);
        tvBadge.setTextColor(Color.WHITE);
        tvBadge.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tvBadge, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        // tab bar: 7 entries, two rows (4 + 3) so the labels stay readable
        String[] names = new String[]{"状态", "白名单", "日志", "功能", "版本", "地图", "账号"};
        tabs = new Button[names.length];
        LinearLayout bar = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 4 == 0) {
                bar = new LinearLayout(this);
                bar.setOrientation(LinearLayout.HORIZONTAL);
                bar.setBackgroundColor(Color.parseColor("#E3F2FD"));
                root.addView(bar);
            }
            final int idx = i;
            Button t = btn(names[i], new View.OnClickListener() {
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            t.setTextSize(13f);
            t.setPadding(0, dp(2), 0, dp(2));
            bar.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabs[i] = t;
        }

        panelHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(panelHost, hostLp);

        panels = new View[]{statusPanel(), whitelistPanel(), logPanel(),
                toolsPanel(), viaPanel(), mapPanel(), accountPanel()};
        for (View p : panels) {
            panelHost.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        rootWrap.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootWrap);
        selectTab(0);
    }

    private void selectTab(int idx) {
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? Color.parseColor("#0D47A1") : Color.parseColor("#616161"));
        }
        if (idx == 1) loadWhitelist();
        if (idx == 3) {                       // 功能
            renderServerList();
            refreshServerState();
            refreshModuleStates();
        }
        if (idx == 4) refreshViaState();        // 版本
        if (idx == 5) loadMapIndex();           // 地图
        if (idx == 6) renderAccounts();         // 账号
        // render the backlog only when the log page becomes visible, then keep to the tail
        if (idx == 2 && logView != null) {
            logDirty = false;
            renderLog();
        }
    }

    // ====================================================== game account switch

    /** Saved MC accounts for the bot, as [{"n":name,"t":"premium|offline"}]. Empty by default. */
    private JSONArray savedAccounts() {
        // one-time migration: earlier builds seeded a few example accounts automatically.
        // The user asked for an empty default, so clear that seed once.
        if (!prefs.getBoolean("accounts_cleared", false)) {
            prefs.edit().putString("accounts", new JSONArray().toString())
                    .putBoolean("accounts_cleared", true).apply();
        }
        String raw = prefs.getString("accounts", "");
        JSONArray arr = null;
        if (raw != null && raw.length() > 0) {
            try {
                arr = new JSONArray(raw);
            } catch (Exception ignored) {
            }
        }
        if (arr == null) {
            arr = new JSONArray();
            prefs.edit().putString("accounts", arr.toString()).apply();
        }
        return arr;
    }

    private void saveAccounts(JSONArray arr) {
        prefs.edit().putString("accounts", arr.toString()).apply();
        renderAccounts();
    }

    private void renderAccounts() {
        if (acctBox == null) return;
        acctBox.removeAllViews();
        JSONArray arr = savedAccounts();

        if (arr.length() == 0) {
            TextView empty = value("（还没有保存的账号，用下面输入框添加）", 13f);
            empty.setTextColor(Color.parseColor("#9E9E9E"));
            acctBox.addView(empty);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String name = o.optString("n", "");
            final String type = o.optString("t", "premium");
            if (name.length() == 0) continue;
            final boolean offline = "offline".equals(type);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(value(name, 15f));
            TextView tag = new TextView(this);
            tag.setTextSize(11f);
            tag.setTextColor(offline ? Color.parseColor("#E65100") : Color.parseColor("#1565C0"));
            tag.setText(offline ? "离线账号" : "正版（微软）");
            col.addView(tag);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button sw = btn("切换", new View.OnClickListener() {
                public void onClick(View v) {
                    switchGameAccount(name, offline);
                }
            });
            sw.setTextSize(12f);
            row.addView(sw);

            Button del = btn("删除", new View.OnClickListener() {
                public void onClick(View v) {
                    JSONArray a = savedAccounts();
                    JSONArray keep = new JSONArray();
                    for (int k = 0; k < a.length(); k++) {
                        JSONObject e = a.optJSONObject(k);
                        if (e == null) continue;
                        if (e.optString("n", "").equals(name)
                                && e.optString("t", "premium").equals(offline ? "offline" : "premium")) {
                            continue;
                        }
                        keep.put(e);
                    }
                    saveAccounts(keep);
                }
            });
            del.setTextSize(12f);
            row.addView(del);
            acctBox.addView(row);
        }
    }

    private void addAccount() {
        String name = acctName.getText().toString().trim();
        if (name.length() == 0) {
            setAcctMsg("先输入游戏 ID");
            return;
        }
        if (name.indexOf(' ') >= 0) {
            setAcctMsg("游戏 ID 不能带空格");
            return;
        }
        boolean offline = acctOffline.isChecked();
        JSONArray arr = savedAccounts();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            if (e.optString("n", "").equalsIgnoreCase(name)
                    && e.optString("t", "premium").equals(offline ? "offline" : "premium")) {
                setAcctMsg("这个账号已经在列表里了");
                return;
            }
        }
        try {
            arr.put(new JSONObject().put("n", name).put("t", offline ? "offline" : "premium"));
        } catch (Exception ignored) {
        }
        acctName.setText("");
        setAcctMsg("已添加 " + name);
        saveAccounts(arr);
    }

    /**
     * Switches the account the BOT logs into the target server with.
     * premium -> device code flow (cached token reused when it's the same account)
     * offline -> ZenithProxy's unsupported offline auth
     */
    private void switchGameAccount(final String name, final boolean offline) {
        final Api a = api;
        if (a == null || !loggedIn) {
            setAcctMsg("还没连上面板");
            return;
        }
        if (offline) {
            setAcctMsg("注意：离线账号只能连允许离线的服务器；正版认证服会直接拒绝"
                    + "（Cannot join online mode server with offline auth）。仍要试就点「切换」。");
        } else {
            setAcctMsg("正在切换到 " + name + "（" + (offline ? "离线" : "正版") + "）…");
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    a.command("disconnect");
                    Thread.sleep(1500);
                    if (offline) {
                        a.command("unsupported auth type offline");
                        Thread.sleep(600);
                        a.command("unsupported auth offlineUsername " + name);
                        Thread.sleep(600);
                    } else {
                        a.command("auth type deviceCode");
                        Thread.sleep(400);
                        // only wipe the cache when switching to a different premium account,
                        // so returning to the same one doesn't need a fresh device code
                        String last = prefs.getString("lastPremium", "");
                        if (!name.equalsIgnoreCase(last)) {
                            a.command("auth clear");
                            Thread.sleep(600);
                        }
                        prefs.edit().putString("lastPremium", name).apply();
                    }
                    String out = a.commandText("connect");
                    handleAuthText(out);
                    ui.post(new Runnable() {
                        public void run() {
                            setAcctMsg("已发送切换指令，等 25 秒看结果…");
                        }
                    });
                    Thread.sleep(25000);
                    final String outcome = lastAuthOutcome();
                    JSONObject d = a.dashboard();
                    final boolean conn = d.optBoolean("connected", false);
                    final String user = d.optString("username", "?");
                    ui.post(new Runnable() {
                        public void run() {
                            if (conn) {
                                setAcctMsg("切换成功，当前在线账号：" + user);
                            } else {
                                setAcctMsg("切换后未上线。" + (outcome.length() > 0 ? " 原因：" + outcome : " 看日志页"));
                            }
                            pollDashboard();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            setAcctMsg("切换失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** Pulls the most recent auth-related line out of the buffered logs. */
    private String lastAuthOutcome() {
        for (int i = logLines.size() - 1; i >= 0 && i >= logLines.size() - 80; i--) {
            String t = logLines.get(i).text;
            if (t.contains("Cannot join online mode server")
                    || t.contains("Login Failed")
                    || t.contains("无法加入")
                    || t.contains("already registered by an online account")
                    || t.contains("登录失败")) {
                return t.replace("【系统】", "").replace("【个人死亡】", "").trim();
            }
            if (t.contains("已进入服务器") || t.contains("已登录为")) {
                return "";
            }
        }
        return "";
    }

    private static final String ACC_PREFS_NOTE = "";
    private View statusPanel() {
        ScrollView sc = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(10), dp(14), dp(14));
        sc.addView(v);

        tvErr = new TextView(this);
        tvErr.setTextColor(Color.parseColor("#C62828"));
        tvErr.setTextSize(13f);
        tvErr.setVisibility(View.GONE);
        v.addView(tvErr);

        // primary state
        v.addView(label("机器人状态"));
        tvAcct = bigStat("账号: -");
        tvSrv = bigStat("服务器: -");
        tvOnline = bigStat("在线时长: -");
        v.addView(tvAcct);
        v.addView(tvSrv);
        v.addView(tvOnline);

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(4));
        btnUp = btn("上线", new View.OnClickListener() {
            public void onClick(View x) {
                sendCmd("connect", "上线");
            }
        });
        btnDown = btn("下线", new View.OnClickListener() {
            public void onClick(View x) {
                sendCmd("disconnect", "下线");
            }
        });
        btnRefresh = btn("刷新", new View.OnClickListener() {
            public void onClick(View x) {
                pollDashboard();
                pollStatus();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(btnUp, blp);
        row.addView(btnDown, new LinearLayout.LayoutParams(blp));
        row.addView(btnRefresh, new LinearLayout.LayoutParams(blp));
        v.addView(row);

        // vitals
        v.addView(label("血量"));
        pbHp = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbHp.setMax(20);
        pbHp.setProgress(0);
        v.addView(pbHp);
        tvHealth = value(" - / 20", 14f);
        v.addView(tvHealth);

        v.addView(label("饱食度"));
        pbFood = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbFood.setMax(20);
        pbFood.setProgress(0);
        v.addView(pbFood);
        tvFood = value(" - / 20", 14f);
        v.addView(tvFood);

        // latency
        v.addView(label("延迟（近 60 次采样）"));
        tvPing = value(" - ms", 15f);
        v.addView(tvPing);
        chart = new PingChart(this);
        int ch = dp(90);
        v.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ch));

        // queue + coords
        v.addView(label("排队"));
        tvQueue = value(" -", 14f);
        v.addView(tvQueue);

        v.addView(label("坐标 / 维度"));
        tvCoords = value(" -", 15f);
        tvDim = value(" -", 14f);
        v.addView(tvCoords);
        v.addView(tvDim);

        v.addView(label("主机资源"));
        tvSys = value(" -", 13f);
        v.addView(tvSys);

        v.addView(label("隐私"));
        cbHide = new CheckBox(this);
        cbHide.setText("隐藏坐标与服务器 IP（截图防泄露）");
        cbHide.setTextSize(12f);
        cbHide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                applyPrivacy();
            }
        });
        v.addView(cbHide);

        return sc;
    }

    private View whitelistPanel() {
        ScrollView sc = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(10), dp(14), dp(14));
        sc.addView(v);

        wlHead = new TextView(this);
        wlHead.setTextSize(14f);
        wlHead.setTextColor(Color.parseColor("#424242"));
        wlHead.setText("白名单: -");
        v.addView(wlHead);

        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setPadding(0, dp(8), 0, dp(8));
        wlInput = new EditText(this);
        wlInput.setHint("玩家 ID");
        wlInput.setSingleLine(true);
        wlInput.setTextSize(14f);
        add.addView(wlInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addBtn = btn("添加", new View.OnClickListener() {
            public void onClick(View x) {
                String n = wlInput.getText().toString().trim();
                if (n.length() == 0) {
                    setWlMsg("先输入玩家 ID");
                    return;
                }
                wlAction("add", n);
            }
        });
        add.addView(addBtn);
        v.addView(add);

        wlMsg = new TextView(this);
        wlMsg.setTextSize(13f);
        wlMsg.setTextColor(Color.parseColor("#1565C0"));
        v.addView(wlMsg);

        wlBox = new LinearLayout(this);
        wlBox.setOrientation(LinearLayout.VERTICAL);
        v.addView(wlBox);

        Button reload = btn("刷新白名单", new View.OnClickListener() {
            public void onClick(View x) {
                loadWhitelist();
            }
        });
        v.addView(reload);

        return sc;
    }

    private View logPanel() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(6), dp(8), dp(6));

        logBtns = new Button[CAT_NAMES.length];
        // two rows of three so the Chinese labels stay readable on a phone
        for (int rowIdx = 0; rowIdx < 2; rowIdx++) {
            LinearLayout rowLl = new LinearLayout(this);
            rowLl.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 3; i++) {
                final int cat = rowIdx * 3 + i;
                if (cat >= CAT_NAMES.length) break;
                Button b = btn(CAT_NAMES[cat], new View.OnClickListener() {
                    public void onClick(View x) {
                        logFilter = cat;
                        restyleLogButtons();
                        renderLog();
                    }
                });
                b.setTextSize(12f);
                b.setPadding(0, dp(2), 0, dp(2));
                rowLl.addView(b, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                logBtns[cat] = b;
            }
            v.addView(rowLl);
        }

        Button clear = btn("清空本页显示", new View.OnClickListener() {
            public void onClick(View x) {
                logLines.clear();
                renderLog();
            }
        });
        v.addView(clear);

        CheckBox follow = new CheckBox(this);
        follow.setText("自动滚到最新");
        follow.setTextSize(12f);
        follow.setChecked(followTail);
        follow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                followTail = checked;
                if (checked) renderLog();
            }
        });
        v.addView(follow);

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTextColor(Color.parseColor("#263238"));
        logView.setBackgroundColor(Color.parseColor("#ECEFF1"));
        logView.setPadding(dp(6), dp(6), dp(6), dp(6));
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        v.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        restyleLogButtons();
        return v;
    }

    private void restyleLogButtons() {
        if (logBtns == null) return;
        for (int i = 0; i < logBtns.length; i++) {
            if (logBtns[i] == null) continue;
            logBtns[i].setBackgroundColor(i == logFilter
                    ? Color.parseColor("#90CAF9") : Color.parseColor("#E0E0E0"));
            logBtns[i].setTextColor(i == logFilter
                    ? Color.parseColor("#0D47A1") : Color.parseColor("#424242"));
        }
    }

    private View accountPanel() {
        ScrollView sc = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(10), dp(14), dp(14));
        sc.addView(v);

        v.addView(label("服务器地址（面板 IP:端口）"));
        edHost = new EditText(this);
        edHost.setHint("例如 203.0.113.10:8080");
        edHost.setSingleLine(true);
        edHost.setTextSize(14f);
        v.addView(edHost);

        v.addView(label("端口（若上面已带端口，可留空）"));
        edPort = new EditText(this);
        edPort.setHint("8080");
        edPort.setSingleLine(true);
        edPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        edPort.setTextSize(14f);
        v.addView(edPort);

        v.addView(label("面板用户名"));
        edUser = new EditText(this);
        edUser.setHint("admin");
        edUser.setSingleLine(true);
        edUser.setTextSize(14f);
        v.addView(edUser);

        v.addView(label("面板密码"));
        edPass = new EditText(this);
        edPass.setHint("密码");
        edPass.setSingleLine(true);
        edPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        edPass.setTextSize(14f);
        v.addView(edPass);

        // Lets the user eyeball what the soft keyboard actually produced - full-width
        // punctuation (！ vs !) from Chinese IMEs is the usual cause of a 401.
        final CheckBox showPass = new CheckBox(this);
        showPass.setText("显示密码（确认没有全角字符或多余空格）");
        showPass.setTextSize(12f);
        showPass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                int pos = edPass.getSelectionStart();
                if (checked) {
                    edPass.setTransformationMethod(android.text.method.HideReturnsTransformationMethod.getInstance());
                } else {
                    edPass.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                }
                edPass.setSelection(pos);
            }
        });
        v.addView(showPass);

        // Where the panel password actually lives - the usual "I don't know the password"
        // question when this APK is handed to somebody else.
        TextView pwdWhere = new TextView(this);
        pwdWhere.setTextSize(12f);
        pwdWhere.setTextColor(Color.parseColor("#C62828"));
        pwdWhere.setText("不知道密码？它在服务器这个文件里：\n"
                + "/root/ZenithProxy/plugins/webui/config.json\n"
                + "找到 \"auth\" 下面的 \"password\" 字段即是；改完需要 systemctl restart zenith。\n"
                + "（用户名默认 admin，也在同一个文件里。）");
        v.addView(pwdWhere);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        Button save = btn("保存并连接", new View.OnClickListener() {
            public void onClick(View x) {
                savePrefs();
                connectAsync();
            }
        });
        row.addView(save, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        v.addView(row);

        tvAcctMsg = new TextView(this);
        tvAcctMsg.setTextSize(13f);
        tvAcctMsg.setTextColor(Color.parseColor("#1565C0"));
        v.addView(tvAcctMsg);

        v.addView(label("游戏账号（切换机器人登录的 MC 账号）"));

        acctBox = new LinearLayout(this);
        acctBox.setOrientation(LinearLayout.VERTICAL);
        v.addView(acctBox);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, dp(6), 0, dp(6));
        acctName = new EditText(this);
        acctName.setHint("游戏 ID");
        acctName.setSingleLine(true);
        acctName.setTextSize(14f);
        addRow.addView(acctName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addBtn = btn("添加", new View.OnClickListener() {
            public void onClick(View x) {
                addAccount();
            }
        });
        addBtn.setTextSize(12f);
        addRow.addView(addBtn);
        v.addView(addRow);

        acctOffline = new CheckBox(this);
        acctOffline.setText("这是离线账号（不走微软认证）");
        acctOffline.setTextSize(12f);
        v.addView(acctOffline);

        acctMsg2 = new TextView(this);
        acctMsg2.setTextSize(13f);
        acctMsg2.setTextColor(Color.parseColor("#1565C0"));
        acctMsg2.setPadding(0, dp(6), 0, dp(6));
        v.addView(acctMsg2);

        v.addView(label("微软设备码（切换正版账号时出现）"));
        tvDevice = new TextView(this);
        tvDevice.setTextSize(15f);
        tvDevice.setTextColor(Color.parseColor("#0D47A1"));
        tvDevice.setPadding(0, dp(8), 0, dp(8));
        tvDevice.setMovementMethod(LinkMovementMethod.getInstance());
        v.addView(tvDevice);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setTextColor(Color.parseColor("#757575"));
        hint.setText("正版账号：切到同一个号会复用已缓存令牌，不弹码；换成另一个微软账号才需要设备码授权。"
                + "离线账号：用 ZenithProxy 的 unsupported 离线认证，"
                + "但正版认证服对客户端强制校验，离线账号会被直接拒绝（实测 "
                + "Cannot join online mode server with offline auth），只有换到允许离线的服务器才有用。");
        v.addView(hint);

        return sc;
    }

    // ================================================================== prefs

    private void loadPrefs() {
        String host = prefs.getString("host", "");
        String port = prefs.getString("port", String.valueOf(DEF_PORT));
        edHost.setText(host);
        edPort.setText(port);
        edUser.setText(prefs.getString("user", "admin"));
        edPass.setText(prefs.getString("pass", ""));
        hideSensitive = prefs.getBoolean("hideSensitive", false);
        if (cbHide != null) cbHide.setChecked(hideSensitive);
    }

    private void savePrefs() {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("host", edHost.getText().toString().trim());
        e.putString("port", edPort.getText().toString().trim());
        e.putString("user", edUser.getText().toString().trim());
        e.putString("pass", edPass.getText().toString());
        e.apply();
    }

    private void setAcctMsg(String s) {
        if (tvAcctMsg != null) tvAcctMsg.setText(s);
    }

    private void setWlMsg(String s) {
        if (wlMsg != null) wlMsg.setText(s);
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showErr(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (s == null || s.length() == 0) {
                    tvErr.setVisibility(View.GONE);
                } else {
                    tvErr.setText(s);
                    tvErr.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    // ================================================================ connect

    private void connectAsync() {
        final String hostRaw = edHost.getText().toString().trim();
        final String portRaw = edPort.getText().toString().trim();
        final String user = edUser.getText().toString().trim();
        final String pass = edPass.getText().toString();

        if (hostRaw.length() == 0) {
            setAcctMsg("服务器地址不能为空");
            return;
        }
        if (user.length() == 0 || pass.length() == 0) {
            setAcctMsg("用户名和密码都要填（用户名通常是 admin，密码见面板配置文件）");
            return;
        }
        String[] hp = Api.splitHostPort(hostRaw, DEF_PORT);
        final String host = hp[0];
        final int port = (portRaw.length() > 0) ? parseInt(portRaw, DEF_PORT) : parseInt(hp[1], DEF_PORT);

        setAcctMsg("连接中… " + host + ":" + port);
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Api a = new Api(host, port);
                    String saved = prefs.getString("cookie", "");
                    boolean ok = false;
                    if (saved != null && saved.length() > 0) {
                        a.setCookie(saved);
                        try {
                            JSONObject st = a.authStatus();
                            ok = st.optBoolean("authenticated", false);
                        } catch (Exception ignored) {
                        }
                    }
                    if (!ok) {
                        ok = a.login(user, pass);
                    }
                    if (!ok) throw new Exception("认证失败，检查用户名/密码");

                    final String ck = a.cookie();
                    prefs.edit().putString("cookie", ck == null ? "" : ck).apply();

                    api = a;
                    loggedIn = true;
                    sseRun[0] = true;
                    ui.post(new Runnable() {
                        public void run() {
                            setAcctMsg("已连接面板 " + host + ":" + port);
                            showErr("");
                            pollDashboard();
                            pollStatus();
                            ui.removeCallbacks(dashTask);
                            ui.removeCallbacks(statTask);
                            ui.postDelayed(dashTask, 1500);
                            ui.postDelayed(statTask, 2500);
                        }
                    });
                    startSse();
                } catch (final Exception e) {
                    loggedIn = false;
                    final String friendly = friendlyLoginError(e.getMessage());
                    ui.post(new Runnable() {
                        public void run() {
                            setAcctMsg("连接失败: " + friendly);
                        }
                    });
                }
            }
        }).start();
    }

    /** Turns raw HTTP failures into something actionable on a phone screen. */
    private static String friendlyLoginError(String raw) {
        String m = raw == null ? "" : raw;
        String low = m.toLowerCase();
        if (m.contains("401")) {
            return "用户名或密码错误（HTTP 401）。请点「显示密码」确认没有全角字符（！而不是 !）"
                    + "或多余空格；插件限制同一 IP 每分钟 5 次失败，超过会锁 60 秒";
        }
        if (m.contains("429") || low.contains("rate limit")) {
            return "登录过于频繁被限流，请等 60 秒再试";
        }
        if (low.contains("failed to connect") || low.contains("timed out")
                || low.contains("econnrefused")) {
            return "连不上面板 " + m + "，检查端口是否放行、地址是否正确";
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ================================================================= polling

    private final Runnable dashTask = new Runnable() {
        public void run() {
            if (!alive) return;
            pollDashboard();
            ui.postDelayed(this, 2000);
        }
    };

    private final Runnable statTask = new Runnable() {
        public void run() {
            if (!alive) return;
            pollStatus();
            ui.postDelayed(this, 12000);
        }
    };

    private void pollDashboard() {
        final Api a = api;
        if (a == null || !loggedIn) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject d = a.dashboard();
                    ui.post(new Runnable() {
                        public void run() {
                            applyDashboard(d);
                            showErr("");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            showErr("读取状态失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void pollStatus() {
        final Api a = api;
        if (a == null || !loggedIn) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String txt = a.commandText("status");
                    ui.post(new Runnable() {
                        public void run() {
                            applyStatus(txt);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void applyDashboard(JSONObject d) {
        boolean conn = d.optBoolean("connected", false);
        String user = d.optString("username", "-");
        String proxy = d.optString("proxyAddress", "-");

        tvBadge.setText(conn ? "● 在线" : "● 离线");
        tvBadge.setTextColor(Color.WHITE);

        tvAcct.setText("账号: " + user);
        botUsername = user;
        String shownProxy = hideSensitive ? maskAddress(proxy) : proxy;
        // 这里的版本号改成实时的 ViaVersion（dashboard.viaVersion），不再显示代理自身的版本
        String via = d.optString("viaVersion", "").trim();
        tvSrv.setText("服务器: " + shownProxy
                + (via.length() > 0 ? "（" + via + "）" : ""));

        JSONObject p = d.optJSONObject("player");
        double hp = -1, food = -1;
        if (p != null) {
            if (!p.isNull("health")) hp = p.optDouble("health", -1);
            if (!p.isNull("food")) food = p.optDouble("food", -1);
        }
        if (hp >= 0) {
            pbHp.setProgress((int) Math.round(hp));
            tvHealth.setText(String.format(" %.0f / 20", hp));
        } else {
            pbHp.setProgress(0);
            tvHealth.setText(" 未连接");
        }
        if (food >= 0) {
            pbFood.setProgress((int) Math.round(food));
            tvFood.setText(String.format(" %.0f / 20", food));
        } else {
            pbFood.setProgress(0);
            tvFood.setText(" 未连接");
        }

        JSONObject lat = d.optJSONObject("latency");
        if (lat != null) {
            int ms = lat.optInt("botLatency", -1);
            if (ms >= 0) {
                tvPing.setText(" " + ms + " ms");
                // sample every poll so the sparkline shows jitter, not just changes
                chart.push(ms);
                lastLatencyValue = ms;
            } else {
                tvPing.setText(" 未连接");
            }
        }

        boolean inQ = d.optBoolean("inQueue", false);
        int qp = d.optInt("queuePosition", 0);
        int qo = d.optInt("queueOnlinePlayers", 0);
        String queuePos = d.optString("queuePos", "");
        if (inQ) {
            tvQueue.setText("排队中 —— 位置 " + qp + (qo > 0 ? " / 在线 " + qo : "")
                    + (queuePos.length() > 0 ? "\n" + queuePos : ""));
        } else {
            tvQueue.setText(conn ? "未在排队" : "未连接");
        }

        JSONObject sys = d.optJSONObject("system");
        if (sys != null) {
            tvSys.setText(String.format("CPU %.0f%%   内存 %.0f%%\n下行 %.1f KB/s   上行 %.1f KB/s",
                    sys.optDouble("cpuLoad", 0) * 100,
                    sys.optDouble("memoryPercent", 0),
                    sys.optDouble("netBytesPerSecIn", 0) / 1024.0,
                    sys.optDouble("netBytesPerSecOut", 0) / 1024.0));
        }

        btnUp.setEnabled(!conn);
        btnDown.setEnabled(true);
    }

    private int lastLatencyValue = -1;

    private void applyStatus(String txt) {
        if (txt == null || txt.length() == 0) return;

        String online = Api.field(txt, "Online Duration");
        tvOnline.setText("在线时长: " + (online.length() > 0 ? online : "-"));

        String dim = Api.field(txt, "Dimension");
        tvDim.setText("维度: " + (dim.length() > 0 ? dim : "-"));

        String srv = Api.field(txt, "Server");
        if (srv.length() > 0) currentServer = srv;

        Integer[] xyz = Api.parseCoords(txt);
        if (xyz != null) {
            if (xyz[0] == 0 && xyz[1] == 0 && xyz[2] == 0 && online.length() == 0) {
                tvCoords.setText("坐标: 未连接");
            } else if (hideSensitive) {
                tvCoords.setText("坐标: ●●●, ●●●, ●●●（已隐藏）");
            } else {
                tvCoords.setText("坐标: " + xyz[0] + ", " + xyz[1] + ", " + xyz[2]);
            }
        }
    }

    /** Masks host:port as ***.***.***.***:port so screenshots don't leak the address. */
    private static String maskAddress(String addr) {
        if (addr == null || addr.length() == 0) return "-";
        int colon = addr.lastIndexOf(':');
        String port = colon > 0 ? addr.substring(colon) : "";
        return "***.***.***.***" + port + "（已隐藏）";
    }

    /** Re-renders the masked/unmasked values without waiting for the next poll. */
    private void applyPrivacy() {
        hideSensitive = cbHide != null && cbHide.isChecked();
        prefs.edit().putBoolean("hideSensitive", hideSensitive).apply();
        if (tvCoords != null) {
            String cur = tvCoords.getText().toString();
            if (hideSensitive) {
                if (cur.startsWith("坐标:") && !cur.contains("未连接") && !cur.contains("已隐藏")) {
                    tvCoords.setText("坐标: ●●●, ●●●, ●●●（已隐藏）");
                }
            } else if (cur.contains("已隐藏")) {
                tvCoords.setText("坐标: 刷新中…");
                pollStatus();
            }
        }
        pollDashboard();
    }

    // ================================================================ commands

    private void sendCmd(final String cmd, final String label) {
        final Api a = api;
        if (a == null || !loggedIn) {
            toast("尚未连接面板");
            return;
        }
        toast(label + " 已发送");
        new Thread(new Runnable() {
            public void run() {
                try {
                    a.command(cmd);
                    ui.post(new Runnable() {
                        public void run() {
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    pollDashboard();
                                }
                            }, 1200);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            showErr(label + " 失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** Looks for a Microsoft device-code link/code in arbitrary text and surfaces it. */
    private void handleAuthText(String text) {
        if (text == null) return;
        Matcher lm = P_LINK.matcher(text);
        Matcher cm = P_CODE.matcher(text);
        final String url = lm.find() ? lm.group(1).replaceAll("[)\\],;]+$", "") : null;
        final String code = cm.find() ? cm.group(1) : null;
        if (url == null && code == null) return;
        ui.post(new Runnable() {
            public void run() {
                if (tvDevice == null) return;
                StringBuilder sb = new StringBuilder();
                if (url != null) {
                    sb.append("打开: <a href=\"").append(url).append("\">").append(url).append("</a>");
                }
                if (code != null) {
                    if (sb.length() > 0) sb.append("<br/>");
                    sb.append("设备码: <b>").append(code).append("</b>");
                }
                tvDevice.setText(Html.fromHtml(sb.toString()));
            }
        });
    }

    // =============================================================== whitelist

    private void loadWhitelist() {
        final Api a = api;
        if (a == null || !loggedIn) {
            setWlMsg("尚未连接面板");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject o = a.whitelist();
                    ui.post(new Runnable() {
                        public void run() {
                            renderWhitelist(o);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            setWlMsg("读取白名单失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void renderWhitelist(JSONObject o) {
        wlBox.removeAllViews();
        boolean enabled = o.optBoolean("enabled", false);
        String kick = o.optString("kickmsg", "");
        JSONArray arr = o.optJSONArray("whitelist");
        int n = arr == null ? 0 : arr.length();
        wlHead.setText("白名单(" + n + ")  启用: " + (enabled ? "是" : "否")
                + (kick.length() > 0 ? "   踢出提示: " + kick : ""));
        if (arr == null) return;
        for (int i = 0; i < n; i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            final String name = e.optString("username", "?");
            String uuid = e.optString("uuid", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView nm = value(name, 15f);
            TextView id = new TextView(this);
            id.setText(uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid);
            id.setTextSize(11f);
            id.setTextColor(Color.parseColor("#9E9E9E"));
            col.addView(nm);
            col.addView(id);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = btn("删除", new View.OnClickListener() {
                public void onClick(View x) {
                    wlAction("remove", name);
                }
            });
            del.setTextSize(12f);
            row.addView(del);
            wlBox.addView(row);
        }
    }

    private void wlAction(final String action, final String username) {
        final Api a = api;
        if (a == null || !loggedIn) {
            setWlMsg("尚未连接面板");
            return;
        }
        setWlMsg(("add".equals(action) ? "添加 " : "删除 ") + username + " …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    a.whitelistAction(action, username);
                    ui.post(new Runnable() {
                        public void run() {
                            setWlMsg("已" + ("add".equals(action) ? "添加 " : "删除 ") + username);
                            wlInput.setText("");
                            loadWhitelist();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            setWlMsg("操作失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // =================================================================== logs

    private void startSse() {
        if (sseActive || api == null || !loggedIn) return;
        sseActive = true;
        final Api a = api;
        new Thread(new Runnable() {
            public void run() {
                try {
                    a.streamLogs(sseRun, new Api.LogSink() {
                        public void onLog(JSONObject entry) {
                            onLogEntry(entry);
                        }

                        public void onError(String msg) {
                            // silent: will retry below
                        }
                    });
                } finally {
                    sseActive = false;
                }
                // auto-reconnect while the app is alive
                if (alive && loggedIn) {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                    startSse();
                }
            }
        }).start();
    }

    private void onLogEntry(JSONObject e) {
        final String level = e.optString("level", "INFO");
        final String logger = e.optString("logger", "");
        String msg = e.optString("message", "");
        long ts = e.optLong("timestamp", 0L);

        // device code can arrive on the log stream rather than command output
        if (msg.contains("Login Here") || msg.contains("microsoft.com/link")) {
            handleAuthText(msg);
        }

        final String timeStr = ts > 0 ? fmtTime(ts) : "--:--:--";

        // Visual-range events arrive as a multi-line block; stitch them into one line and
        // drop the UUID. A null result means "not a visual-range message".
        String vr = visualRangeStep(msg);
        if (vr != null) {
            if (vr.length() == 0) return;                 // swallowed fragment
            final LogLine vrLine = new LogLine(timeStr, CAT_NEARBY,
                    ("ERROR".equalsIgnoreCase(level) ? "⚠ " : "") + "【附近玩家】 " + vr);
            ui.post(new Runnable() {
                public void run() {
                    appendLog(vrLine);
                }
            });
            return;
        }

        final int cat = classify(logger, msg);
        // player speech is kept verbatim: translating it would mangle what people actually said
        String body = (cat == CAT_CHAT) ? msg.replace("\n", " / ").trim() : translate(msg);
        final String text = ("ERROR".equalsIgnoreCase(level) ? "⚠ " : "")
                + "【" + CAT_NAMES[cat] + "】 " + body;

        final LogLine line = new LogLine(timeStr, cat, text);
        ui.post(new Runnable() {
            public void run() {
                appendLog(line);
            }
        });
    }

    // ------------------------------------------------------- visual range block

    private static final Pattern VR_ENTER = Pattern.compile(
            "(?:\\[VisualRange\\]\\s*)?([A-Za-z0-9_|]+)\\s+entered visual range\\s*\\[([^\\]]+)\\]");
    private static final Pattern VR_LEAVE = Pattern.compile(
            "(?:\\[VisualRange\\]\\s*)?([A-Za-z0-9_|]+)\\s+left visual range");
    private static final Pattern VR_NAME = Pattern.compile("Player Name\\s*\\n?\\s*(\\S+)");
    private static final Pattern VR_COORDS = Pattern.compile("Coordinates\\s*\\n?\\s*\\[([^\\]]+)\\]");
    private static final Pattern VR_ANY_COORDS = Pattern.compile("\\[\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\]");
    private static final Pattern VR_BARE_COORDS = Pattern.compile("(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)");

    // fragments state (ZenithProxy may emit the block as separate log messages)
    private String vrReason = null;
    private String vrName = null;
    private String vrExpect = null;

    /**
     * @return null  -> not a visual-range message, handle normally
     * ""           -> a fragment that was consumed
     * otherwise    -> the composed single-line body
     */
    private String visualRangeStep(String msg) {
        String m = msg == null ? "" : msg.trim();

        // module one-liners
        java.util.regex.Matcher en = VR_ENTER.matcher(m);
        if (en.find()) {
            return "有玩家进入视野 / 玩家名 / " + en.group(1) + " / 玩家坐标" + coordsInts(en.group(2));
        }
        java.util.regex.Matcher lv = VR_LEAVE.matcher(m);
        if (lv.find()) {
            return "有玩家离开视野 / 玩家名 / " + lv.group(1);
        }

        // whole block in one message
        if (m.contains("Visual Range") && m.indexOf('\n') >= 0) {
            String reason = m.contains("Logout") ? "有玩家在视野内下线" : "有玩家进入视野";
            java.util.regex.Matcher nm = VR_NAME.matcher(m);
            java.util.regex.Matcher cd = VR_COORDS.matcher(m);
            if (cd.find()) {
                String name = nm.find() ? nm.group(1) : "?";
                return reason + " / 玩家名 / " + name + " / 玩家坐标" + coordsInts(cd.group(1));
            }
        }

        // fragment-by-fragment path
        if (m.equals("Player In Visual Range")) {
            startVr("有玩家进入视野");
            return "";
        }
        if (m.equals("Player Logout In Visual Range")) {
            startVr("有玩家在视野内下线");
            return "";
        }
        if (vrReason == null) return null;

        if (m.startsWith("Player Name")) {
            vrExpect = "name";
            return "";
        }
        if (m.startsWith("Player UUID")) {
            vrExpect = "uuid";
            return "";
        }
        if (m.startsWith("Coordinates")) {
            vrExpect = "coords";
            return "";
        }
        if ("name".equals(vrExpect)) {
            vrName = m;
            vrExpect = null;
            return "";
        }
        if ("uuid".equals(vrExpect)) {
            vrExpect = null;      // UUID line dropped on purpose
            return "";
        }
        if ("coords".equals(vrExpect)) {
            String out = vrReason + " / 玩家名 / " + (vrName == null ? "?" : vrName)
                    + " / 玩家坐标" + coordsInts(m);
            vrReason = null;
            vrName = null;
            vrExpect = null;
            return out;
        }
        return null;
    }

    private void startVr(String reason) {
        vrReason = reason;
        vrName = null;
        vrExpect = null;
    }

    /** "245.72, 73.0, 201.13" (with or without brackets) -> "[245, 73, 201]" */
    private static String coordsInts(String raw) {
        if (raw == null) return "[?]";
        String s = raw.trim();
        java.util.regex.Matcher m = VR_ANY_COORDS.matcher(s);
        if (!m.find()) {
            // the multi-line block prints a bare "x, y, z" without brackets
            m = VR_BARE_COORDS.matcher(s);
            if (!m.find()) return "[" + s + "]";
        }
        try {
            long x = Math.round(Double.parseDouble(m.group(1)));
            long y = Math.round(Double.parseDouble(m.group(2)));
            long z = Math.round(Double.parseDouble(m.group(3)));
            return "[" + x + ", " + y + ", " + z + "]";
        } catch (Exception e) {
            return "[" + s + "]";
        }
    }

    /**
     * Buckets a console message. ZenithProxy reports deaths of other players through the
     * chat logger, so the message text (not just the logger) decides the bucket.
     */
    private int classify(String logger, String msg) {
        String lg = logger == null ? "" : logger.toLowerCase(Locale.US);
        String m = msg == null ? "" : msg;
        if (lg.contains("death")) return CAT_MY_DEATH;
        if (lg.contains("visualrange")
                || m.contains("Visual Range") || m.contains("visual range")) return CAT_NEARBY;
        if (P_DEATH.matcher(m).find()) {
            if (botUsername != null && botUsername.length() > 0 && m.contains(botUsername)) {
                return CAT_MY_DEATH;
            }
            return CAT_SRV_DEATH;
        }
        if (m.indexOf('«') >= 0) return CAT_CHAT;   // in-game chat relay format: «name» text
        return CAT_SYS;
    }

    /** English console text -> Chinese. Precompiled once; unmatched text passes through. */
    private String translate(String msg) {
        if (msg == null) return "";
        initTranslations();
        String out = msg.replace("\n", " / ").trim();
        for (int i = 0; i < TR_PATTERNS.length; i++) {
            if (TR_PATTERNS[i] == null) continue;
            try {
                out = TR_PATTERNS[i].matcher(out).replaceAll(TR_REPL[i]);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Pattern[] TR_PATTERNS;
    private static String[] TR_REPL;

    private static synchronized void initTranslations() {
        if (TR_PATTERNS != null) return;
        int n = TRANSLATIONS.length / 2;
        Pattern[] ps = new Pattern[n];
        String[] rs = new String[n];
        for (int i = 0; i < n; i++) {
            try {
                ps[i] = Pattern.compile(TRANSLATIONS[i * 2]);
            } catch (Exception e) {
                ps[i] = null;
            }
            rs[i] = TRANSLATIONS[i * 2 + 1];
        }
        TR_PATTERNS = ps;
        TR_REPL = rs;
    }

    private static final String[] TRANSLATIONS = {
            // --- session / auth
            "Logged in as ([^\\s\\[]+)", "已登录为 $1",
            "Logging in ([^\\s.]+)", "正在登录 $1",
            "Login Here: (\\S+) with code: (\\S+)", "登录地址 $1 ，设备码 $2",
            "Logged in with online account", "已验证为在线（正版）账号",
            "Microsoft Device Code Login", "微软设备码登录",
            "Unable to connect to login", "无法登录服务器",
            // --- connection
            "Connecting to the server", "正在连接服务器",
            "Connecting to ([^\\s]+:[0-9]+)", "正在连接 $1",
            "Connected to host\\.([^/]+)/([0-9.:]+)", "已连接主机 $1（$2）",
            "Disconnecting from server", "正在断开连接",
            "Disconnected: (.*)", "已断开：$1",
            "Connection closed", "连接已关闭",
            "Connection disconnected: ([^ ]+)", "连接断开：$1",
            "Manual Disconnect", "手动断开",
            "AutoReconnecting in ([0-9]+)s", "将在 $1 秒后自动重连",
            "Reconnecting in ([0-9]+)s", "$1 秒后重连",
            "Cancelling AutoReconnect because disconnect reason is not reconnectable",
            "此断开原因不可重连，已取消自动重连",
            // --- server bootstrap / plugins
            "Server started", "代理服务已启动",
            "Starting server on (\\S+)", "代理正在监听 $1",
            "Started LAN server broadcaster", "局域网广播已启动",
            "Server teleport \\d+ to: (.*)", "服务器将你传送到 $1",
            "Server enforces secure chat, chat signing enabled", "服务器强制安全聊天，已启用消息签名",
            "No custom chat schema found for server: ([^,]+),.*", "未找到 $1 的聊天格式，聊天解析可能不准",
            "WebUI plugin loaded! Access at (\\S+)", "面板插件已加载：$1",
            "WebUI plugin loaded", "面板插件已加载",
            "Netty NIO server started on port ([0-9]+)", "面板服务已启动，端口 $1",
            "Queue parser registered", "排队解析已注册",
            "Title packet handler registered", "字幕包处理器已注册",
            "AuthBypasser packet handlers registered.*", "离线认证绕过已注册",
            "Plugin Loaded", "插件已加载",
            "Found Plugin", "发现插件",
            "Online", "已进入服务器",
            "Reconfiguring", "正在重新配置",
            "Loading block connection mappings", "正在加载方块映射",
            "Loading translations", "正在加载多语言表",
            "Registering protocols", "正在注册协议",
            // --- queue / whitelist / rate limit
            "has passed the whitelist check with auth: (\\S+)", "已通过白名单校验，权限 $1",
            "tried to connect!", "尝试连接",
            "Non-Whitelisted Login Blocked", "非白名单登录已被拦截",
            "no whitelist", "不在白名单内",
            "Login Rate Limited", "登录过于频繁，已被限流",
            "Position in queue: ([0-9]+)", "队列位置 $1",
            // --- visual range
            "([A-Za-z0-9_|]+) entered visual range \\[(.*?)\\]", "玩家 $1 进入视野（$2）",
            "([A-Za-z0-9_|]+) left visual range", "玩家 $1 离开视野",
            "Player In Visual Range", "有玩家进入视野",
            "Player Logout In Visual Range", "有玩家在视野内下线",
            "Player Name", "玩家名",
            "Player UUID", "玩家 UUID",
            // --- deaths
            "drowned", "淹死了",
            "was slain by ([^\\s.]+)", "被 $1 杀死",
            "was shot by ([^\\s.]+)", "被 $1 射杀",
            "was killed by ([^\\s.]+)", "被 $1 杀死",
            "was blown up by ([^\\s.]+)", "被 $1 炸死",
            "was fireballed by ([^\\s.]+)", "被 $1 的火球击中而死",
            "was pummeled by ([^\\s.]+)", "被 $1 殴打致死",
            "was impaled by ([^\\s.]+)", "被 $1 刺穿",
            "was pricked to death", "被仙人掌扎死",
            "blew up", "被炸死",
            "fell from a high place", "从高处坠落而死",
            "fell out of the world", "掉出了世界",
            "hit the ground too hard", "摔落致死",
            "burned to death", "被烧死",
            "went up in flames", "被烧死",
            "starved to death", "饿死了",
            "suffocated in a wall", "在方块里窒息而死",
            "withered away", "凋零而死",
            "froze to death", "冻死了",
            "was struck by lightning", "被雷劈死",
            "experienced kinetic energy", "动能过大摔死",
            "tried to swim in lava", "在岩浆里游泳",
            "was killed by magic", "被魔法杀死",
            "died", "死亡",
            // --- 功能页命令回执
            ": on\\b", "：开启",
            ": off\\b", "：关闭",
            "AntiAFK:", "防挂机:",
            "AutoEat:", "自动进食:",
            "Auto Totem:", "自动图腾:",
            "AutoArmor:", "自动穿甲:",
            "AutoMend:", "自动修补:",
            "AntiKick:", "防踢出:",
            "AutoDisconnect:", "危险自动下线:",
            "AutoReply:", "自动回复:",
            "KillAura:", "自动攻击:",
            "Map Generated!", "地图已生成",
            "Map Rendered!", "地图已渲染",
            "All Cached Maps Rendered", "已渲染全部缓存地图",
            "Map Count: (\\d+)", "地图数量: $1",
            "Map ID: (\\d+)", "地图 ID: $1",
            "Task List", "任务列表",
            "None\\b", "（无）",
            "Invalid command usage", "命令用法不正确",
            "^Usage: ", "用法: ",
            "Health Threshold: (\\d+)", "血量阈值: $1",
            "Hunger Threshold: (\\d+)", "饥饿阈值: $1",
            "Attack Delay Ticks: (\\d+)", "攻击间隔(刻): $1",
            "In Game:", "仅在游戏内:",
            "Weapon Switching:", "自动换武器:",
            "Target Players:", "目标玩家:",
            "Target Hostile Mobs:", "目标敌对生物:",
            "Target Neutral Mobs:", "目标中立生物:",
            "Raycast:", "视线检测:",
            "TPS Sync:", "TPS 同步:",
            "^IP: (\\S+)", "服务器 IP: $1",
            "^Port: (\\d+)", "端口: $1",
            "Auto Add Zenith Account:", "自动添加机器人账号:",
    };

    /**
     * Log appends are buffered and flushed at most a few times per second.
     * A busy server emits dozens of lines per second; calling setText() per line was what
     * made opening the log page janky on a phone.
     */
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    /** SimpleDateFormat isn't thread-safe; SSE calls arrive off the UI thread. */
    private static String fmtTime(long ts) {
        synchronized (TIME_FMT) {
            return TIME_FMT.format(new Date(ts));
        }
    }

    private final Runnable logFlush = new Runnable() {
        public void run() {
            logDirty = false;
            renderLog();
        }
    };

    private void appendLog(LogLine line) {
        logLines.add(line);
        while (logLines.size() > MAX_LOG_LINES) logLines.remove(0);
        if (logFilter != CAT_ALL && logFilter != line.cat) return;
        // the log page isn't visible: just keep buffering, render when the user opens it
        if (panels == null || panels[2].getVisibility() != View.VISIBLE) return;
        if (!logDirty) {
            logDirty = true;
            ui.postDelayed(logFlush, 400);
        }
    }

    private void renderLog() {
        if (logView == null) return;

        boolean atBottom = true;
        if (logScroll != null && followTail && logScroll.getChildCount() > 0) {
            View child = logScroll.getChildAt(0);
            atBottom = child.getBottom() - (logScroll.getScrollY() + logScroll.getHeight()) < dp(40);
        }

        // One backward pass finds the oldest row we still want to show, so the TextView
        // never holds more than RENDER_LINES rows no matter how chatty the server is.
        int startIdx = -1;
        int taken = 0;
        for (int i = logLines.size() - 1; i >= 0; i--) {
            LogLine l = logLines.get(i);
            if (logFilter != CAT_ALL && l.cat != logFilter) continue;
            startIdx = i;
            taken++;
            if (taken >= RENDER_LINES) break;
        }

        StringBuilder sb = new StringBuilder();
        // prefix tells you which bot account these events belong to
        String acc = (botUsername == null || botUsername.length() == 0) ? "未登录" : botUsername;
        String prefix = "《(" + acc + ")》 ";
        if (startIdx >= 0) {
            for (int i = startIdx; i < logLines.size() && sb.length() < MAX_LOG_CHARS; i++) {
                LogLine l = logLines.get(i);
                if (logFilter != CAT_ALL && l.cat != logFilter) continue;
                sb.append(prefix).append(l.time).append(' ').append(l.text).append('\n');
            }
        }
        if (sb.length() == 0) {
            sb.append(logFilter == CAT_ALL
                    ? "（暂无日志）"
                    : "（" + CAT_NAMES[logFilter] + " 暂无记录）");
        }
        logView.setText(sb.toString());

        if (followTail && atBottom && logScroll != null) {
            final ScrollView sv = logScroll;
            sv.post(new Runnable() {
                public void run() {
                    View child = sv.getChildAt(0);
                    if (child != null) sv.scrollTo(0, child.getBottom());
                }
            });
        }
    }
    // ================================================================ tools tab

    private void toolSection(LinearLayout p, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13f);
        t.setTextColor(Color.parseColor("#0D47A1"));
        t.setPadding(0, dp(14), 0, dp(4));
        p.addView(t);
    }

    private LinearLayout toolRow(LinearLayout p) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        p.addView(r);
        return r;
    }

    private EditText toolInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14f);
        return e;
    }

    private View toolsPanel() {
        ScrollView sc = new ScrollView(this);
        toolsScroll = sc;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(12), dp(8), dp(12), dp(16));
        sc.addView(v);

        // output box first so results are always a scroll-to-top away
        v.addView(label("运行结果"));
        toolOut = new TextView(this);
        toolOut.setTextSize(11f);
        toolOut.setTextColor(Color.parseColor("#263238"));
        toolOut.setBackgroundColor(Color.parseColor("#ECEFF1"));
        toolOut.setPadding(dp(6), dp(6), dp(6), dp(6));
        toolOut.setTypeface(android.graphics.Typeface.MONOSPACE);
        toolOut.setTextIsSelectable(true);
        toolOut.setText("（还没执行命令）");
        v.addView(toolOut);

        toolMsg = new TextView(this);
        toolMsg.setTextSize(13f);
        toolMsg.setTextColor(Color.parseColor("#1565C0"));
        toolMsg.setPadding(0, dp(6), 0, 0);
        v.addView(toolMsg);

        // ---------------------------------------------------- target server
        toolSection(v, "目标服务器");
        toolServer = value("当前: 读取中…", 14f);
        v.addView(toolServer);

        LinearLayout srow = toolRow(v);
        srvInput = toolInput("新服务器 ip 或 ip:端口");
        srow.addView(srvInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        srow.addView(btn("切换", new View.OnClickListener() {
            public void onClick(View x) {
                applyServerInput(srvInput.getText().toString().trim(), true);
            }
        }));
        srow.addView(btn("保存", new View.OnClickListener() {
            public void onClick(View x) {
                saveServerEntry(srvInput.getText().toString().trim());
            }
        }));

        srvBox = new LinearLayout(this);
        srvBox.setOrientation(LinearLayout.VERTICAL);
        v.addView(srvBox);

        // ---------------------------------------------------- modules
        toolSection(v, "自动化模块（状态实时读取）");
        for (int i = 0; i < MODULES.length; i++) {
            final String label = MODULES[i][0];
            final String cmd = MODULES[i][1];
            LinearLayout row = toolRow(v);
            TextView nm = value(label, 14f);
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView st = new TextView(this);
            st.setText("…");
            st.setTextSize(13f);
            st.setTextColor(Color.parseColor("#757575"));
            st.setPadding(0, 0, dp(8), 0);
            row.addView(st);
            modStates.put(cmd, st);
            row.addView(btn("开", new View.OnClickListener() {
                public void onClick(View x) {
                    runTool(cmd + " on", label + " 开会");
                }
            }));
            row.addView(btn("关", new View.OnClickListener() {
                public void onClick(View x) {
                    runTool(cmd + " off", label + " 关掉");
                }
            }));
        }
        v.addView(btn("刷新全部模块状态", new View.OnClickListener() {
            public void onClick(View x) {
                refreshModuleStates();
            }
        }));
        TextView ka = new TextView(this);
        ka.setTextSize(11f);
        ka.setTextColor(Color.parseColor("#C62828"));
        ka.setText("⚠️ KillAura / 自动战斗类功能在多数公共服务器违反服规，风险自负。");
        v.addView(ka);

        // ---------------------------------------------------- pathfinder
        toolSection(v, "寻路（Baritone）");
        LinearLayout prow = toolRow(v);
        pathX = toolInput("x");
        pathZ = toolInput("z");
        prow.addView(pathX, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        prow.addView(pathZ, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        prow.addView(btn("前往", new View.OnClickListener() {
            public void onClick(View x) {
                String sx = pathX.getText().toString().trim();
                String sz = pathZ.getText().toString().trim();
                if (sx.length() == 0 || sz.length() == 0) {
                    setToolMsg("先填 x 和 z");
                    return;
                }
                runTool("pathfinder goto " + sx + " " + sz, "前往 " + sx + "," + sz);
            }
        }));

        LinearLayout frow = toolRow(v);
        pathPlayer = toolInput("玩家名");
        frow.addView(pathPlayer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        frow.addView(btn("跟随", new View.OnClickListener() {
            public void onClick(View x) {
                String p = pathPlayer.getText().toString().trim();
                if (p.length() == 0) {
                    setToolMsg("先填玩家名");
                    return;
                }
                runTool("pathfinder follow " + p, "跟随 " + p);
            }
        }));

        LinearLayout brow = toolRow(v);
        pathBlock = toolInput("方块 id，如 diamond_ore");
        brow.addView(pathBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brow.addView(btn("去挖", new View.OnClickListener() {
            public void onClick(View x) {
                String b = pathBlock.getText().toString().trim();
                if (b.length() == 0) {
                    setToolMsg("先填方块 id");
                    return;
                }
                runTool("pathfinder mine " + b, "挖 " + b);
            }
        }));

        LinearLayout srow2 = toolRow(v);
        srow2.addView(btn("停止寻路", new View.OnClickListener() {
            public void onClick(View x) {
                runTool("pathfinder stop", "停止寻路");
            }
        }));
        srow2.addView(btn("寻路状态", new View.OnClickListener() {
            public void onClick(View x) {
                runTool("pathfinder status", "寻路状态");
            }
        }));

        // ---------------------------------------------------- info
        toolSection(v, "信息查询");
        LinearLayout irow = toolRow(v);
        infoName = toolInput("玩家名（stats / seen 用）");
        irow.addView(infoName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        irow.addView(btn("统计", new View.OnClickListener() {
            public void onClick(View x) {
                String n = infoName.getText().toString().trim();
                if (n.length() == 0) {
                    setToolMsg("先填玩家名");
                    return;
                }
                runTool("stats " + n, "2b2t 统计 " + n);
            }
        }));
        irow.addView(btn("上次在线", new View.OnClickListener() {
            public void onClick(View x) {
                String n = infoName.getText().toString().trim();
                if (n.length() == 0) {
                    setToolMsg("先填玩家名");
                    return;
                }
                runTool("seen " + n, "上次在线 " + n);
            }
        }));

        LinearLayout irow2 = toolRow(v);
        String[][] infoBtns = {
                {"游戏时长", "playtime"},
                {"背包", "inventory"},
                {"Tab 列表", "tablist"},
                {"准星方块", "raycast"},
                {"路径点", "waypoints list"},
                {"队列", "queueStatus"},
                {"任务列表", "tasks list"},
        };
        int col = 0;
        LinearLayout cur = irow2;
        for (int i = 0; i < infoBtns.length; i++) {
            final String cmd = infoBtns[i][1];
            final String lbl = infoBtns[i][0];
            if (col == 3) {
                cur = toolRow(v);
                col = 0;
            }
            Button b = btn(lbl, new View.OnClickListener() {
                public void onClick(View x) {
                    runTool(cmd, lbl);
                }
            });
            b.setTextSize(12f);
            cur.addView(b, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col++;
        }

        // ---------------------------------------------------- chat & raw command
        toolSection(v, "说话 / 任意命令");
        LinearLayout crow = toolRow(v);
        chatInput = toolInput("要发到游戏里的聊天");
        crow.addView(chatInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        crow.addView(btn("发送", new View.OnClickListener() {
            public void onClick(View x) {
                String m = chatInput.getText().toString();
                if (m.length() == 0) {
                    setToolMsg("先输入聊天内容");
                    return;
                }
                runTool("sendMessage " + m, "发送聊天");
                chatInput.setText("");
            }
        }));

        LinearLayout rrow = toolRow(v);
        rawCmd = toolInput("任意 ZenithProxy 命令，如 status");
        rrow.addView(rawCmd, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rrow.addView(btn("执行", new View.OnClickListener() {
            public void onClick(View x) {
                String c = rawCmd.getText().toString().trim();
                if (c.length() == 0) {
                    setToolMsg("先输入命令");
                    return;
                }
                runTool(c, "命令");
            }
        }));

        TextView hint = new TextView(this);
        hint.setTextSize(11f);
        hint.setTextColor(Color.parseColor("#757575"));
        hint.setText("提示：多条命令可以用 multi 一次执行，用 ,, 分隔。"
                + "比如 pathfinder goto 0 0,,sendMessage 出发了");
        v.addView(hint);

        return sc;
    }

    // ------------------------------------------------------------ tools logic

    private void setToolMsg(String s) {
        if (toolMsg != null) toolMsg.setText(s);
    }

    /** Runs one ZenithProxy command and shows its output in the tools tab. */
    private void runTool(final String cmd, final String desc) {
        final Api a = api;
        if (a == null || !loggedIn) {
            setToolMsg("还没连上面板");
            return;
        }
        setToolMsg(desc + " …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String rawOut = a.commandText(cmd);
                    final String out = rawOut.length() == 0 ? "" : translate(rawOut);
                    ui.post(new Runnable() {
                        public void run() {
                            toolOut.setText("$ " + cmd + "\n" + (out.length() == 0 ? "（无输出）" : out));
                            setToolMsg(desc + " 完成");
                            if (toolsScroll != null) toolsScroll.scrollTo(0, 0);
                        }
                    });
                    // module commands echo their state; refresh the badges after a toggle
                    if (cmd.startsWith("anti") || cmd.startsWith("auto")
                            || cmd.startsWith("killAura")) {
                        String base = cmd.split(" ")[0];
                        if (modStates.containsKey(base)) refreshOneModule(a, base);
                    }
                    if (cmd.startsWith("via")) refreshViaState();
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            toolOut.setText("$ " + cmd + "\n失败: " + e.getMessage());
                            setToolMsg(desc + " 失败");
                        }
                    });
                }
            }
        }).start();
    }

    private void refreshModuleStates() {
        refreshServerState();
        refreshViaState();
        for (int i = 0; i < MODULES.length; i++) {
            refreshOneModule(api, MODULES[i][1]);
        }
    }

    // ------------------------------------------------------------ viaversion

    private void refreshViaState() {
        final Api a = api;
        if (a == null || !loggedIn || viaState == null) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String out = a.commandText("via");
                    final String zs = Api.field(out, "Zenith To Server ViaVersion");
                    final String ver = Api.field(out, "Zenith To Server Version");
                    final String pz = Api.field(out, "Player To Zenith ViaVersion");
                    final String d2b = Api.field(out, "Zenith To Server Disable On 2b2t");
                    ui.post(new Runnable() {
                        public void run() {
                            viaState.setText("机器人→服务器: " + onOffCn(zs)
                                    + "　版本: " + (ver.length() > 0 ? ver : "?")
                                    + "\n玩家→代理: " + onOffCn(pz)
                                    + "　连 2b2t 时自动关: " + onOffCn(d2b));
                            highlightViaSelection(ver);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private static String onOffCn(String v) {
        if (v == null) return "?";
        String s = v.trim();
        if (s.equalsIgnoreCase("on")) return "开";
        if (s.equalsIgnoreCase("off")) return "关";
        return s.length() == 0 ? "?" : s;
    }

    /** ViaVersion changes only take effect on a fresh connection. */
    private void reconnectForVia() {
        final Api a = api;
        if (a == null || !loggedIn) {
            toast("还没连上面板");
            return;
        }
        toast("正在重连以应用跨版本设置…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    a.command("disconnect");
                    Thread.sleep(2000);
                    a.command("connect");
                    Thread.sleep(15000);
                    JSONObject d = a.dashboard();
                    final boolean conn = d.optBoolean("connected", false);
                    final String via = d.optString("viaVersion", "");
                    ui.post(new Runnable() {
                        public void run() {
                            toast(conn ? "已重连，当前跨版本：" + via : "重连后未上线，看日志页");
                            pollDashboard();
                        }
                    });
                    refreshViaState();
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            toast("重连失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void refreshOneModule(final Api a, final String cmd) {
        if (a == null) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String out = a.commandText(cmd);
                    final String state = firstOnOff(out);
                    ui.post(new Runnable() {
                        public void run() {
                            TextView t = modStates.get(cmd);
                            if (t == null) return;
                            boolean on = "on".equalsIgnoreCase(state);
                            t.setText(state.length() == 0 ? "?" : (on ? "已开" : "已关"));
                            t.setTextColor(on ? Color.parseColor("#2E7D32")
                                    : Color.parseColor("#9E9E9E"));
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /** Pulls the first "on"/"off" out of a module status dump. */
    private static String firstOnOff(String out) {
        if (out == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(":\\s*(on|off)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(out);
        if (m.find()) return m.group(1).toLowerCase(Locale.US);
        return "";
    }

    private void refreshServerState() {
        final Api a = api;
        if (a == null || !loggedIn) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String out = a.commandText("server");
                    final String ip = Api.field(out, "IP");
                    final String port = Api.field(out, "Port");
                    ui.post(new Runnable() {
                        public void run() {
                            String s = ip.length() > 0 ? ip + (port.length() > 0 ? ":" + port : "") : "未知";
                            toolServer.setText("当前: " + s);
                            currentServer = ip;
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void applyServerInput(String input, boolean thenConnect) {
        if (input.length() == 0) {
            setToolMsg("先填服务器地址");
            return;
        }
        String[] hp = Api.splitHostPort(input, 25565);
        String cmd = "server " + hp[0] + (hp[1].length() > 0 ? " " + hp[1] : "");
        runTool(cmd, "切换服务器到 " + hp[0]);
        if (thenConnect) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(1500);
                        if (api != null) api.command("connect");
                        Thread.sleep(4000);
                        refreshServerState();
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
    }

    private void saveServerEntry(String input) {
        if (input.length() == 0) {
            setToolMsg("先填服务器地址");
            return;
        }
        JSONArray arr = savedServers();
        for (int i = 0; i < arr.length(); i++) {
            if (input.equalsIgnoreCase(arr.optString(i))) {
                setToolMsg("已经在列表里");
                return;
            }
        }
        arr.put(input);
        prefs.edit().putString("servers", arr.toString()).apply();
        setToolMsg("已保存 " + input);
        renderServerList();
    }

    private JSONArray savedServers() {
        String raw = prefs.getString("servers", "");
        JSONArray arr = null;
        if (raw != null && raw.length() > 0) {
            try {
                arr = new JSONArray(raw);
            } catch (Exception ignored) {
            }
        }
        if (arr == null) {
            arr = new JSONArray();
            prefs.edit().putString("servers", arr.toString()).apply();
        }
        return arr;
    }

    private void renderServerList() {
        if (srvBox == null) return;
        srvBox.removeAllViews();
        JSONArray arr = savedServers();
        for (int i = 0; i < arr.length(); i++) {
            final String s = arr.optString(i, "");
            if (s.length() == 0) continue;
            LinearLayout row = toolRow(srvBox);
            TextView nm = value(s, 14f);
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(btn("切过去", new View.OnClickListener() {
                public void onClick(View x) {
                    applyServerInput(s, true);
                }
            }));
            row.addView(btn("删", new View.OnClickListener() {
                public void onClick(View x) {
                    JSONArray a = savedServers();
                    JSONArray keep = new JSONArray();
                    for (int k = 0; k < a.length(); k++) {
                        if (!s.equalsIgnoreCase(a.optString(k))) keep.put(a.optString(k));
                    }
                    prefs.edit().putString("servers", keep.toString()).apply();
                    renderServerList();
                }
            }));
        }
    }

    // ================================================================== map tab

    // ================================================================== via tab

    private View viaPanel() {
        ScrollView sc = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(12), dp(8), dp(12), dp(16));
        sc.addView(v);

        v.addView(label("当前跨版本状态（读 via 命令的实时回显）"));
        viaState = new TextView(this);
        viaState.setTextSize(14f);
        viaState.setTextColor(Color.parseColor("#0D47A1"));
        viaState.setPadding(dp(10), dp(10), dp(10), dp(10));
        viaState.setBackgroundColor(Color.parseColor("#E3F2FD"));
        viaState.setText("读取中…");
        v.addView(viaState);

        v.addView(label("开关"));
        LinearLayout r1 = toolRow(v);
        r1.addView(btn("机器人→服务器 开", new View.OnClickListener() {
            public void onClick(View x) {
                runTool("via zenithToServer on", "开启机器人→服务器 跨版本");
            }
        }));
        r1.addView(btn("关", new View.OnClickListener() {
            public void onClick(View x) {
                runTool("via zenithToServer off", "关闭机器人→服务器 跨版本");
            }
        }));

        LinearLayout r2 = toolRow(v);
        r2.addView(btn("玩家→代理 开", new View.OnClickListener() {
            public void onClick(View x) {
                runTool("via playerToZenith on", "开启玩家→代理 跨版本");
            }
        }));
        r2.addView(btn("关", new View.OnClickListener() {
            public void onClick(View x) {
                runTool("via playerToZenith off", "关闭玩家→代理 跨版本");
            }
        }));

        Button reconnect = btn("重连生效", new View.OnClickListener() {
            public void onClick(View x) {
                reconnectForVia();
            }
        });
        reconnect.setBackgroundColor(Color.parseColor("#1565C0"));
        reconnect.setTextColor(Color.WHITE);
        v.addView(reconnect);

        v.addView(label("机器人连服用的 MC 版本（点一行即切换，选中行变绿）"));
        for (int i = 0; i < VIA_VERSIONS.length; i++) {
            final String val = VIA_VERSIONS[i];
            Button b = new Button(this);
            b.setText("auto".equals(val) ? "自动（跟随服务器）" : val);
            b.setAllCaps(false);
            b.setTextSize(15f);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    applyViaPick(val);
                }
            });
            v.addView(b);
            viaVersionBtns.add(b);
            viaVersionLabels.add(val);
        }

        TextView hint = new TextView(this);
        hint.setTextSize(11f);
        hint.setTextColor(Color.parseColor("#C62828"));
        hint.setPadding(0, dp(10), 0, 0);
        hint.setText("点一行只会改配置，正在跑的连接不会变——改完点上面的「重连生效」。"
                + "如果跨版本原本是关的，会自动帮你打开。"
                + "降级到老版本时，新版本独有的方块/物品显示会不同，属正常映射。");
        v.addView(hint);

        return sc;
    }

    /** 点一行版本：改版本（必要时顺带打开跨版本），然后刷新高亮。 */
    private void applyViaPick(final String label) {
        final Api a = api;
        if (a == null || !loggedIn) {
            toast("还没连上面板");
            return;
        }
        toast("正在切换到 " + ("auto".equals(label) ? "自动" : label) + " …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    if ("auto".equals(label)) {
                        a.command("via zenithToServer version auto");
                    } else {
                        a.command("via zenithToServer version " + label);
                    }
                    // 只改版本不会生效：跨版本若是关的就顺手打开
                    String st = a.commandText("via");
                    if (st.contains("Zenith To Server ViaVersion: off")) {
                        a.command("via zenithToServer on");
                    }
                    Thread.sleep(400);
                    refreshViaState();
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            toast("切换失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 依据服务器回显的版本串，把对应那一行涂绿（取最长匹配，避免 1.21 抢走 1.21.1）。 */
    private void highlightViaSelection(String serverVersionText) {
        if (viaVersionBtns.isEmpty()) return;
        String t = serverVersionText == null ? "" : serverVersionText.toLowerCase(Locale.US);
        String best = null;
        if (t.startsWith("auto")) {
            // 自动模式回显形如 "Auto (1.20.3-1.20.4)"，此时应点亮「自动」这一行
            best = "auto";
        } else {
            for (int i = 0; i < viaVersionLabels.size(); i++) {
                String lbl = viaVersionLabels.get(i);
                if ("auto".equals(lbl)) continue;
                if (t.contains(lbl) && (best == null || lbl.length() > best.length())) best = lbl;
            }
        }

        for (int i = 0; i < viaVersionBtns.size(); i++) {
            boolean sel = viaVersionLabels.get(i).equals(best);
            Button b = viaVersionBtns.get(i);
            b.setBackgroundColor(sel ? Color.parseColor("#2E7D32") : Color.parseColor("#E0E0E0"));
            b.setTextColor(sel ? Color.WHITE : Color.parseColor("#424242"));
            b.setTypeface(null, sel
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private View mapPanel() {
        ScrollView sc = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(12), dp(8), dp(12), dp(16));
        sc.addView(v);

        v.addView(label("可视距离（区块，越大越慢；受已缓存区块限制）"));
        LinearLayout r = toolRow(v);
        mapDist = toolInput("6");
        mapDist.setText("6");
        r.addView(mapDist, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(btn("生成周围地图", new View.OnClickListener() {
            public void onClick(View x) {
                generateMap();
            }
        }));
        r.addView(btn("刷新列表", new View.OnClickListener() {
            public void onClick(View x) {
                loadMapIndex();
            }
        }));
        r.addView(btn("清空全部", new View.OnClickListener() {
            public void onClick(View x) {
                clearAllMaps();
            }
        }));

        mapMsg = new TextView(this);
        mapMsg.setTextSize(13f);
        mapMsg.setTextColor(Color.parseColor("#1565C0"));
        mapMsg.setPadding(0, dp(6), 0, dp(6));
        mapMsg.setText("点「生成周围地图」：机器人会在当前位置生成地图并渲染成 PNG。");
        v.addView(mapMsg);

        TextView note = new TextView(this);
        note.setTextSize(11f);
        note.setTextColor(Color.parseColor("#C62828"));
        note.setText("说明：每张图是 128×128 的独立地图切片，ZenithProxy 不输出切片的世界坐标，"
                + "所以无法自动拼成一张无缝大地图。点图放大，点右上角 × 删除单张。");
        v.addView(note);

        mapBig = new ImageView(this);
        mapBig.setAdjustViewBounds(true);
        mapBig.setVisibility(View.GONE);
        v.addView(mapBig);

        mapGrid = new LinearLayout(this);
        mapGrid.setOrientation(LinearLayout.VERTICAL);
        mapGrid.setPadding(0, dp(8), 0, 0);
        v.addView(mapGrid);

        return sc;
    }

    private void generateMap() {
        final Api a = api;
        if (a == null || !loggedIn) {
            setMapMsg("还没连上面板");
            return;
        }
        String d = mapDist.getText().toString().trim();
        if (d.length() == 0) d = "6";
        final String dist = d;
        setMapMsg("正在生成（视野 " + dist + "）…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    a.command("map generate " + dist);
                    Thread.sleep(3000);
                    ui.post(new Runnable() {
                        public void run() {
                            setMapMsg("渲染中…");
                        }
                    });
                    a.command("map render all");
                    Thread.sleep(5000);
                    loadMapIndex();
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            setMapMsg("生成失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void setMapMsg(String s) {
        if (mapMsg != null) mapMsg.setText(s);
    }

    private static final class MapFile {
        final String name;
        final long time;

        MapFile(String n, long t) {
            name = n;
            time = t;
        }
    }

    /**
     * The plugin's static handler caches files by path for the whole process lifetime,
     * so a single mutable index.json would never refresh. The server therefore writes
     * index_<bucket>.json every 20s (new path = never cached). We probe a few buckets
     * around our own clock, learning the server/client offset from 'generated'.
     */
    private void loadMapIndex() {
        final Api a = api;
        if (a == null || !loggedIn) {
            setMapMsg("还没连上面板");
            return;
        }
        setMapMsg("读取地图列表…");
        new Thread(new Runnable() {
            public void run() {
                final long clientNow = System.currentTimeMillis() / 1000L;
                long off = prefs.getLong("srvClockOff", 0L);
                long baseBucket = (clientNow + off) / 20L;
                int[] deltas = {0, -1, 1, -2, 2, -3, 3, -5, 5, -8, 8, -15, 15, -30, 30};

                JSONObject best = null;
                for (int i = 0; i < deltas.length && best == null; i++) {
                    long bucket = baseBucket + deltas[i];
                    if (bucket <= 0) continue;
                    JSONObject o = fetchJson(a, "/maps/index_" + bucket + ".json");
                    if (o != null && o.optLong("generated", 0) > 0) best = o;
                }
                if (best == null) best = fetchJson(a, "/maps/index.json");   // may be stale
                if (best == null) {
                    ui.post(new Runnable() {
                        public void run() {
                            setMapMsg("读不到地图索引：确认服务器上 mapindex.timer 在跑");
                        }
                    });
                    return;
                }

                long gen = best.optLong("generated", 0);
                if (gen > 0) prefs.edit().putLong("srvClockOff", gen - clientNow).apply();
                final long age = gen > 0 ? (clientNow + prefs.getLong("srvClockOff", 0)) - gen : -1;
                final JSONArray files = best.optJSONArray("files");
                final java.util.List<MapFile> list = new java.util.ArrayList<MapFile>();
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.optJSONObject(i);
                        if (f == null) continue;
                        String nm = f.optString("n", "");
                        if (nm.endsWith(".png")) list.add(new MapFile(nm, f.optLong("t", 0)));
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        renderMapGrid(list);
                        if (age > 120) setMapMsg(getMapMsg() + "　（索引已 " + age + " 秒未更新）");
                    }
                });
            }
        }).start();
    }

    private String getMapMsg() {
        return mapMsg == null ? "" : mapMsg.getText().toString();
    }

    private JSONObject fetchJson(Api a, String path) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(a.base() + path).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            if (a.cookie() != null) c.setRequestProperty("Cookie", a.cookie());
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void renderMapGrid(java.util.List<MapFile> all) {
        try {
            renderMapGridInner(all);
        } catch (Throwable t) {
            // never let a rendering problem take the whole app down
            setMapMsg("显示地图时出错：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (" " + t.getMessage())));
        }
    }

    private void renderMapGridInner(java.util.List<MapFile> all) {
        // drop old bitmaps before building a new grid so repeated refreshes don't pile up
        for (int i = 0; i < mapGrid.getChildCount(); i++) {
            View rowV = mapGrid.getChildAt(i);
            if (!(rowV instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) rowV;
            for (int k = 0; k < r.getChildCount(); k++) {
                View cellV = r.getChildAt(k);
                if (!(cellV instanceof FrameLayout)) continue;
                FrameLayout cellF = (FrameLayout) cellV;
                for (int c = 0; c < cellF.getChildCount(); c++) {
                    View v = cellF.getChildAt(c);
                    if (v instanceof ImageView) ((ImageView) v).setImageDrawable(null);
                }
            }
        }
        mapGrid.removeAllViews();
        if (all.isEmpty()) {
            setMapMsg("还没有渲染过地图");
            return;
        }
        // filenames look like 2026-10-21_19-11-05_map_85752.png -> group by the render batch
        String newestBatch = "";
        for (int i = 0; i < all.size(); i++) {
            String nm = all.get(i).name;
            int idx = nm.indexOf("_map_");
            if (idx > 0) {
                newestBatch = nm.substring(0, idx);
                break;
            }
        }
        final java.util.List<MapFile> batch = new java.util.ArrayList<MapFile>();
        for (int i = 0; i < all.size() && batch.size() < 12; i++) {
            String nm = all.get(i).name;
            if (nm.startsWith(newestBatch)) batch.add(all.get(i));
        }
        setMapMsg("最新一批：" + newestBatch + "　共 " + batch.size() + " 张（点图放大）");

        int perRow = 3;
        LinearLayout row = null;
        for (int i = 0; i < batch.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                mapGrid.addView(row);
            }
            final MapFile mf = batch.get(i);

            // cell = image + a small delete button pinned to the corner
            FrameLayout cell = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.parseColor("#E0E0E0"));
            iv.setPadding(dp(2), dp(2), dp(2), dp(2));
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    showBigMap(mf.name);
                }
            });
            cell.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            Button del = new Button(this);
            del.setText("×");
            del.setTextSize(13f);
            del.setTextColor(Color.WHITE);
            del.setPadding(0, 0, 0, 0);
            del.setBackgroundColor(Color.parseColor("#C62828"));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    deleteMapFile(mf.name);
                }
            });
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(30), dp(26));
            dlp.gravity = Gravity.TOP | Gravity.END;
            dlp.setMargins(0, dp(3), dp(3), 0);
            cell.addView(del, dlp);

            row.addView(cell, new LinearLayout.LayoutParams(0, dp(110), 1f));
            loadMapTile(iv, mf.name);
        }
    }

    // ------------------------------------------------------- map delete (8081)

    /** URL of the map-admin service. `host` must be resolved on the UI thread. */
    private static String mapAdminBase(String host) {
        return "http://" + host + ":" + MAP_ADMIN_PORT;
    }

    private void deleteMapFile(final String name) {
        mapDelete(new String[]{name}, "删除 " + name);
    }

    private void clearAllMaps() {
        mapDelete(null, "清空全部地图");
    }

    /** names == null means "clear all". */
    private void mapDelete(final String[] names, final String what) {
        final Api a = api;
        if (a == null || !loggedIn) {
            setMapMsg("还没连上面板");
            return;
        }
        setMapMsg(what + " …");
        // Touch the EditTexts here, on the UI thread: reading views from a worker thread
        // is what used to crash this page.
        final String host = Api.splitHostPort(edHost.getText().toString().trim(), DEF_PORT)[0];
        final String user = edUser.getText().toString().trim();
        final String pass = edPass.getText().toString();
        new Thread(new Runnable() {
            public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    String path = names == null ? "/api/maps/clear" : "/api/maps/delete";
                    c = (java.net.HttpURLConnection) new java.net.URL(mapAdminBase(host) + path).openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("Content-Type", "application/json");
                    if (a.cookie() != null) c.setRequestProperty("Cookie", a.cookie());
                    String body;
                    if (names == null) {
                        body = "{\"user\":" + jsonStr(user)
                                + ",\"pass\":" + jsonStr(pass)
                                + ",\"names\":[]}";
                    } else {
                        body = "{\"user\":" + jsonStr(user)
                                + ",\"pass\":" + jsonStr(pass)
                                + ",\"names\":" + toJsonArray(names) + "}";
                    }
                    byte[] data = body.getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(data.length);
                    java.io.OutputStream os = c.getOutputStream();
                    os.write(data);
                    os.flush();
                    os.close();

                    int code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    if (in != null) {
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                        in.close();
                    }
                    final boolean ok = code == 200;
                    final String resp = sb.toString();
                    ui.post(new Runnable() {
                        public void run() {
                            if (ok) {
                                setMapMsg(what + " 完成，正在刷新列表…");
                            } else {
                                setMapMsg(what + " 失败: " + resp
                                        + "（需服务器上 mapadmin 服务在跑，且 8081 已放行）");
                            }
                        }
                    });
                    if (ok) {
                        Thread.sleep(1200);
                        loadMapIndex();
                    }
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            setMapMsg(what + " 失败: " + e.getMessage()
                                    + "（连不上 " + MAP_ADMIN_PORT + "，确认地图服务在跑）");
                        }
                    });
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private static String toJsonArray(String[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(arr[i]));
        }
        return sb.append(']').toString();
    }

    /** Minimal JSON string escaping for the values we build by hand. */
    private static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private void showBigMap(String name) {
        mapBig.setVisibility(View.VISIBLE);
        setMapMsg("放大查看：" + name);
        loadMapTile(mapBig, name);
    }

    private void loadMapTile(final ImageView iv, final String name) {
        final Api a = api;
        if (a == null) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String url = a.base() + "/maps/" + name;
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    if (a.cookie() != null) c.setRequestProperty("Cookie", a.cookie());
                    java.io.InputStream in = c.getInputStream();
                    final Bitmap bm = BitmapFactory.decodeStream(in);
                    in.close();
                    c.disconnect();
                    if (bm == null) return;
                    ui.post(new Runnable() {
                        public void run() {
                            iv.setImageBitmap(bm);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }
}
