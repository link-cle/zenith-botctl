# BotCtl 5.5 — 手机控制台使用说明

文件：`BotCtl-5.5.apk`（57,743 字节，SHA256 `39B452D5A2437B6EC8469454949A5C60F3E73B231FB58375CB1BA7BE2C4D897A`）
包名 `com.dsh.botctl`，版本 5.5（versionCode 35），minSdk 21（Android 5.0+），targetSdk 33
签名证书 SHA-256：`d25e22660aa215807195454c6299ca51f7683ffb8178d5ad8cbbd6071d0d3c99`

---

## 一、安装

1. 把 `BotCtl-5.5.apk` 传到手机（微信/QQ 传文件、USB 拷贝、或手机直接下载均可）
2. 点击安装。首次会提示「未知来源应用」——允许你使用的文件管理器/浏览器安装即可
3. 应用名：**Bot控制台**

> 4.2 起的各版本用同一签名证书，**可直接覆盖升级**。
> 若你手机上还是 4.1（或更早），那一版是用已被删除的旧密钥签的，**必须先卸载**再装 5.5。

---

## 二、首次配置

**5.1 起 APK 里不内置任何服务器地址和密码**（方便把安装包给别人用，不会泄露部署者的 IP 和口令）。
首次打开会停在 **「账号」** 页，需要自己填：

| 字段 | 填什么 |
| --- | --- |
| 服务器地址 | 面板的 `IP:端口`，例如 `你的服务器IP:8080` |
| 端口 | 留空（地址里已含端口） |
| 面板用户名 | 默认 `admin` |
| 面板密码 | 见下方提示 |

点 **「保存并连接」**。配置和令牌存本地（`SharedPreferences`），之后打开自动重连。

**不知道面板密码？** App 的密码输入框下面直接写明了位置：

```
/root/ZenithProxy/plugins/webui/config.json
```

找 `auth` 下的 `password` 字段（用户名是同处的 `username`）。改完要
`systemctl restart zenith` 才生效。

> 端口 8080（面板）与 8081（地图维护）需要在云防火墙放行。

> **历史说明**：4.0 时代口令是 `Zen!th-mc.example.com-2026`，手机上出现过
> `HTTP 401 Invalid username or password`——服务器日志显示用户名正确、只是密码串不匹配，
> 是中文输入法把半角 `!` 打成全角 `！` 之类的问题。此后口令一律用纯字母数字，
> 4.1 还加了「显示密码」勾选框可肉眼核对。

---

## 二·五、游戏账号切换（4.5）

App 现在管理的是**机器人登录 MC 服务器的那个账号**。面板密码那套模块已按你的要求删除
（`/api/panel-password`、改密服务、强制改密提示全部移除，服务器上的 `panelpwd` 服务也已停用删除）。

### 界面

「账号」页 → **游戏账号（切换机器人登录的 MC 账号）**：

- **列表默认为空**，不再预置任何账号（5.0 起；升级时会把旧的预置条目清空一次）
- 每行两个按钮：**切换** / **删除**
- 下方输入框 + 「这是离线账号」勾选框 + **添加**，自己加什么就用什么

### 切换逻辑

| 类型 | 实际执行的命令 |
| --- | --- |
| 正版、且是本机上次用过的那个号 | `disconnect` → `auth type deviceCode` → `connect`（复用认证缓存，**不弹设备码**） |
| 正版、换另一个微软账号 | `disconnect` → `auth type deviceCode` → `auth clear` → `connect`（会弹新设备码） |
| 离线 | `disconnect` → `unsupported auth type offline` → `unsupported auth offlineUsername <名字>` → `connect` |

切换后 App 等 25 秒再查一次 `dashboard`，直接告诉你「切换成功，当前在线账号：X」或失败原因
（会从日志里挑出 `Cannot join online mode server with offline auth` / `Login Failed` 这类关键行）。

### ⚠️ 离线账号在 mc.example.com 上必然失败（已实测）

我实际在服务器上跑过一遍：

```
unsupported auth type offline        → Offline Authentication: on, accountType=offline
unsupported auth offlineUsername botoffline7  → 设置成功
connect                              → Logging in botoffline7...
                                     → Disconnected: Cannot join online mode server with offline auth
```

原因：**mc.example.com 对客户端强制正版认证**。离线认证连上去，服务端会直接拒绝握手，
ZenithProxy 也写明 `Cannot join online mode server with offline auth`。

注意区分两种连接，别混淆：

| 连接 | 能否离线 |
| --- | --- |
| 玩家 → 你的代理（`YOUR_SERVER_IP:25565`） | ✅ 可以，靠 `verifyUsers=false` + `allowOfflinePlayers=on` + 白名单 |
| 机器人 → mc.example.com | ❌ 不行，mc.example.com 要求正版 |

所以离线条目只在**换成允许离线的服务器**时才有意义。App 里选离线、且当前目标服是 mc.example.com 时，
会先给一句警告再让你决定要不要试。

> 换号会断开一次连接。若离线尝试失败，机器人会停在 offline 认证状态，
> 回列表点一次正版 `YourMainName` 即可恢复（会复用缓存令牌，不用扫码）。

## 二·六、日志页性能（4.3 优化）

4.2 的日志页在服务器聊天密集时会卡，原因是**每收到一条日志就整页重刷**，
并且把最多 600 行全塞进一个 `TextView`。4.3 做了四项改动：

| 改动 | 效果 |
| --- | --- |
| 合并刷新 | 最多每 400 ms 刷一次，不再一条一刷 |
| 只渲染尾部 200 行 | `TextView` 文本量固定，滚动不再卡顿（内存里仍保留 600 条） |
| 页面不可见时不渲染 | 只在「日志」页显示时才更新界面，其它页零开销 |
| 复用时间格式化器 | 不再每条日志新建 `SimpleDateFormat` |

另外新增 **「自动滚到最新」** 勾选框（默认开）：只有当视图本来就在底部时才自动跟随，
你往上翻看旧日志时不会被强行拽回底部。

---

## 三、功能与对应接口

### 「状态」页

| 功能 | 数据来源 | 刷新 |
| --- | --- | --- |
| 在线 / 离线徽标 | `/api/dashboard` → `connected` | 2 秒 |
| 账号、代理地址、**实时 via 版本** | `username` / `proxyAddress` / `viaVersion` | 2 秒 |
| 血量 | `player.health`（0–20） | 2 秒 |
| 饱食度 | `player.food`（0–20） | 2 秒 |
| 延迟 + 波动曲线 | `latency.botLatency`，保留近 60 次采样画折线 | 2 秒 |
| 排队进度 | `inQueue` / `queuePosition` / `queueOnlinePlayers` / `queuePos` | 2 秒 |
| 主机 CPU / 内存 / 上下行 | `system.*` | 2 秒 |
| 坐标、维度、在线时长 | `status` 命令输出解析 | 12 秒 |
| **一键上线 / 下线** | `POST /api/commands` → `connect` / `disconnect` | 点按 |
| 手动刷新 | 立即触发上述两类拉取 | 点按 |

未连接时坐标显示「未连接」，不会把 `[0, 0, 0]` 当成真实坐标。

**隐私开关**：状态页底部有 `隐藏坐标与服务器 IP（截图防泄露）` 勾选框。勾上后坐标显示
`●●●, ●●●, ●●●（已隐藏）`、服务器地址显示 `***.***.***.***:25565（已隐藏）`，
状态持久保存。截屏分享时先勾它。

### 「白名单」页

- 列出全部白名单（用户名 + UUID 前 8 位），显示启用状态与踢出提示语
- 输入玩家 ID → **添加**；每行右侧 **删除**
- 接口：`GET /api/whitelist`、`POST /api/whitelist {"action":"add|remove","username":"..."}`
- ⚠️ **离线玩家加不进来**：接口会去 Mojang 解析 UUID，离线名必然失败（`无法解析用户名`）。
  离线名字只能由服务端改 `config.json` 写入（见部署手册第十节）。

### 「日志」页（4.2 重做，5.1 加账号前缀与附近玩家重排）

**每行最前面带账号前缀 `《(机器人账号)》`**，告诉你这些事件是哪个账号的；随后是
**时间**（`HH:mm:ss`，取自 SSE 的 `timestamp`，本地时区），最后是**中文内容**。

顶部两行共 6 个分类按钮：

| 分类 | 判定依据 |
| --- | --- |
| 全部 | 不筛选 |
| 个人死亡 | `logger=Death`，或死亡消息里出现机器人自己的账号名 |
| 服务器死亡 | 聊天/日志里出现死亡动词，且不是机器人（即别人死了） |
| 附近玩家 | `Visual Range` 相关（进入/离开视野、视野内下线） |
| 聊天 | 游戏内聊天中继，格式 `«玩家名» 内容`（原文不翻译） |
| 系统 | 其余（登录、连接、插件、排队、限流等） |

显示形如：

```
《(YourMainName)》 19:04:31 【个人死亡】 ||YourOfflineName 淹死了
《(YourMainName)》 19:04:44 【服务器死亡】 Steve 被 Zombie 杀死
《(YourMainName)》 19:05:02 【附近玩家】 有玩家进入视野 / 玩家名 / ||SomePlayer / 玩家坐标[208, 73, 203]
《(YourMainName)》 19:05:10 【聊天】 «||smkjmjh» 有人组队吗
《(YourMainName)》 19:05:20 【系统】 已连接主机 mc.example.com（202.189.16.6:25565）
```

**附近玩家为什么被重排**：ZenithProxy 原本把一条视野事件拆成好几行输出
（`Player In Visual Range` / `Player Name` / 名字 / `Player UUID` / UUID / `Coordinates` / 坐标），
5.1 把这堆碎片**拼成一行中文**，并且**按你的要求把 UUID 那行丢掉了**，只保留
`事件 / 玩家名 / 玩家坐标`。坐标统一取整（`245.72` → `246`）。

- 保留最近 600 条；「清空本页显示」只清本地显示
- 中文翻译由约 100 条正则规则完成（英文原文未命中时原样保留，不会丢信息）
- 来源：`GET /api/logs/stream`，断线后 2.5 秒自动重连

### 「账号」页 — 快捷账号切换

点 **「切换账号」** 会依次执行：

```
disconnect → auth clear → connect
```

然后 App 从日志流里抓取微软设备码，显示成：

```
打开: https://www.microsoft.com/link?otc=XXXXXXXX
设备码: XXXXXXXX
```

链接可点击直接跳浏览器。授权完成后回到「状态」页点 **上线** 即可。

> 一个账号只能在一处在线。如果你自己用 `YourMainName` 登录了游戏，机器人会被踢下线；
> 想操作机器人请连代理地址 `YOUR_SERVER_IP:25565`（需在白名单内）。

### 「功能」页（4.6 新增）

把之前只能进终端敲的功能搬进 App，分五块；跨版本功能在独立的「版本」页。顶部是**运行结果**框（等宽字体、可复制），
执行完会自动滚回顶部显示输出。

**1. 目标服务器**
- 显示当前 `IP:端口`（读 `server` 命令的状态回显）
- 输入 `ip` 或 `ip:端口` → **切换**（执行 `server <ip> [端口]`，随后自动 `connect`）
- **保存** 把输入存进本地列表；列表里每条可「切过去 / 删」

**2. 自动化模块**（9 个，带实时状态徽标，**5.0 已全部汉化**）
防挂机 AntiAFK、自动进食 AutoEat、自动图腾 AutoTotem、自动穿甲 AutoArmor、自动修补 AutoMend、
防踢出 AntiKick、危险自动下线 AutoDisconnect、自动回复 AutoReply、自动攻击 KillAura。
每行 **开 / 关** 两个按钮，点完会重新读一次该模块的裸命令来刷新「已开/已关」。
状态来源就是命令回显，例如 `AntiAFK: on`。

> ⚠️ 自动攻击（KillAura）这类在 mc.example.com 等公共服通常违反服规，界面上已标红提示。

**5.0 的输出也做了汉化**：模块名（`AntiAFK:` → `防挂机:`）、状态（`: on` → `：开启`）、
常见回执（`Map Generated!` → `地图已生成`、`None` → `（无）`、`Invalid command usage` → `命令用法不正确`）
以及血量/饥饿阈值、目标类型等字段都翻成中文；没命中的英文原样保留。

**3. 寻路（Baritone）**
- x / z 输入 → **前往**（`pathfinder goto x z`）
- 玩家名 → **跟随**（`pathfinder follow <名>`）
- 方块 id → **去挖**（`pathfinder mine <方块>`）
- **停止寻路** / **寻路状态**

**4. 信息查询**
玩家名 + **统计**（`stats`，2b2t 数据）/ **上次在线**（`seen`）；
另有按钮：游戏时长、背包、Tab 列表、准星方块、路径点、队列、任务列表。

**5. 说话 / 任意命令**
- 输入聊天 → **发送**（`sendMessage <内容>`）
- 输入任意命令 → **执行**（这就是万能入口，App 没做的功能都能在这里敲）
- 支持 `multi 命令1,,命令2` 一次执行多条

### 「版本」页（5.4 独立成页）

跨版本 ViaVersion **单独一页**（不再挤在「功能」页里），控制「机器人用哪个 MC 版本连服务器」。

**状态条**（读 `via` 命令回显，进出页面都会刷新）：

```
机器人→服务器: 开　版本: 1.20.3-1.20.4
玩家→代理: 开　连 2b2t 时自动关: 开
```

**开关按钮**：机器人→服务器 开/关、玩家→代理 开/关，以及蓝色的 **重连生效**。

**版本列表（点选，不用手打）**：

```
自动（跟随服务器）
1.21.4 / 1.21.1 / 1.21
1.20.6 / 1.20.4 / 1.20.2 / 1.20.1 / 1.20
1.19.4 / 1.19.2 / 1.18.2 / 1.17.1 / 1.16.5 / 1.15.2 / 1.14.4 / 1.12.2 / 1.8.9
```

- **点一行即切换**该版本，并把那一行**涂成绿色**（白字加粗）；其余行灰色
- 切换时会顺带检查：如果「机器人→服务器」原来是关的，**自动帮你打开**（否则改版本不生效）
- 绿色行由服务器回显反推：`1.20.3-1.20.4` → 点亮 `1.20.4`；`Auto (...)` → 点亮「自动」
  （取最长匹配，`1.21.1` 不会被 `1.21` 抢走）

> 改完后必须点 **重连生效**（或状态页的上线）才会用到新版本。

### 「地图」页（4.6 新增，5.0 加删除）

- **可视距离**：默认 6 区块（受代理已缓存区块限制，越大越慢）
- **生成周围地图**：依次执行 `map generate <距离>` → 等 3 秒 → `map render all` → 等 5 秒 → 刷新列表
- **刷新列表**：只重新读索引
- **清空全部**：一次删掉服务器上所有渲染图（5.0 新增）
- 下方按「最新一批」显示切片缩略图（每行 3 张），**点图放大**，
  **每张右上角的红色 ×** 删除单张（5.0 新增）

**关于拼接的实话**：ZenithProxy 每张图是独立的 **128×128 地图切片**，命令输出里
**不包含切片的世界坐标**，目录里也没有元数据文件，所以无法自动拼成一张无缝大地图。
我在这里做的是切片画廊 + 放大查看，而不是假装能拼。

**5.1 的稳定性修复**（之前点地图页会崩）：

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 删除时崩溃 | 在**后台线程**里读 `EditText`（`edHost`/`edUser`/`edPass`），Android 不允许 | 改为在 UI 线程先取出字符串，再交给线程 |
| 内存吃紧 | 一次加载 30 张图且旧图不解引用 | 上限降到 **12 张**，重建前先把旧 `ImageView` 的图置空 |
| 极端情况整页崩 | 渲染里没有兜底 | 整个网格渲染包一层 `try/catch`，出错只显示提示不崩 |
| 大图 OOM | 默认堆偏小 | manifest 加 `android:largeHeap="true"` |

**技术细节（值得记一下）**：地图 PNG 落在 `/root/ZenithProxy/maps/`，通过
`plugins/webui/web/maps → ../../maps` 这个软链复用 **8080** 端口读取，没有再开新端口。
但插件的静态文件处理器**按路径永久缓存**（实测 65 秒后仍是旧内容，加查询串也绕不过），
所以用一个 timer 每 20 秒写成 **带时间戳的新文件名** `index_<bucket>.json`，新路径不被缓存；
App 用自己时钟探测几个相邻 bucket，并从 `generated` 字段自学习服务器时钟偏差（实测偏差 -28 秒）。

---

## 四、安全提醒

1. **8080 / 8081 对公网开放**，面板有密码（密码见服务器上的 `plugins/webui/config.json`）。
   建议在阿里云防火墙把 8080 的**来源 IP 限制为你手机/家里的公网 IP**。
2. 密码在 App 里是明文存储在应用私有目录，root 过的手机才可能被读到。
3. 想换面板密码：改服务器上 `/root/ZenithProxy/plugins/webui/config.json` 的
   `auth.password`，然后 `systemctl restart zenith`，再在 App 里填新密码。
4. 不要把 `mc_auth_cache.json`（微软令牌）发给任何人。

---

## 五、自己重新编译 APK

源码在 `app4/`（Windows）与 `/root/botctl4/`（VPS），构建在 VPS 上完成（无需 Gradle/AndroidX）：

```bash
ssh root@YOUR_SERVER_IP
cd /root/botctl4
bash build_apk.sh          # 输出 /root/botctl4/BotCtl-5.5.apk
```

工具链在 `/opt/android/`：`build-tools`（aapt2 / d8 / zipalign / apksigner）+ `platform-34/android.jar`。

### ⚠️ 编译必须用 JDK 17 的 javac

用 **JDK 21 的 javac 会编译成功但 d8 会崩**：

```
Error in MainActivity$10.class:
java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
```

原因：javac 21 为匿名类的合成外部实例参数无条件写入 `MethodParameters` 属性，
且参数名是空的（`<no name>  final mandated`），R8/d8 读这个 null 名字就 NPE。
`-g:none`、`-parameters` 都抑制不掉；**javac 17 根本不写这个属性**（实测计数 0）。

`build_apk.sh` 已固定使用 `/usr/lib/jvm/java-17-openjdk-amd64/bin/javac`，换环境时注意保留。

### 其它构建要点

- 无资源目录（纯代码 UI），所以 `aapt2 link` 只出 `AndroidManifest.xml` + `resources.arsc`
- `classes.dex` 用 Python 写进 APK（避免依赖 `zip` 命令）
- 签名密钥：`/root/botctl4/botctl.keystore`（**已移出 `build/` 目录**，因为构建第一步会
  清空 `build/`；之前放里面导致每次构建换新密钥、每次都要卸载重装）。口令 `botctl123`，
  别名 `botctl`。当前证书 SHA-256 `d25e2266…`，重建已验证指纹不变，可原地升级。
- 翻译规则校验：`python3 /root/botctl4/gen_translate_test.py` 会从 `MainActivity.java`
  抽出 `TRANSLATIONS` 表，用 javac 17 编译成纯 JVM 程序，对真实日志文本跑一遍并打印中英对照。
- ⚠️ 用 PowerShell 改 `AndroidManifest.xml` 要注意编码：`Set-Content -Encoding UTF8` 会加
  BOM，且 `Get-Content` 默认按 ANSI 读会让中文标签变成乱码，`aapt2` 直接报
  `not well-formed`。用 .NET 的 `UTF8Encoding($false)` 或直接用编辑工具写入。

---

## 六、故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| 显示「连接失败: HTTP 401」 | 面板密码错；或 8080 端口没放行 |
| 一直「离线」 | 服务器上 `systemctl status zenith`；`tmux attach -t zenith` 看是否 `Not Online` |
| 血量/饱食度显示「未连接」 | 机器人没连上 mc.example.com，`dashboard.connected=false` 时这两项是 null |
| 延迟曲线空着 | 未连接时 `botLatency=-1`，App 不采样；连上后约 4 秒开始出点 |
| 日志页不动 | 面板被防火墙中间设备切断长连接；切到别的页再回来，或重启 App |
| 坐标不变 | 坐标走 `status` 命令，12 秒一次；点「刷新」可立即拉 |
| 切换账号没出设备码 | 看「服务日志」页，搜 `Login Here`；也可能微软侧限流，等几分钟重试 |
| 装不上 / 解析包错误 | APK 传输损坏，比对 SHA256；或 Android 版本低于 5.0 |

---

## 七、文件清单

| 路径 | 说明 |
| --- | --- |
| `BotCtl-5.5.apk` | 成品安装包 |
| `app4/src/com/dsh/botctl/MainActivity.java` | 主界面与业务逻辑（含日志翻译/分类） |
| `app4/src/com/dsh/botctl/Api.java` | HTTP / SSE / 解析 |
| `app4/src/com/dsh/botctl/PingChart.java` | 延迟波动自绘控件 |
| `app4/AndroidManifest.xml` | 清单 |
| `app4/build_apk.sh` | 一键构建脚本 |
| `app4/test_parsing.py` | 解析逻辑校验（对着真实响应跑） |
| `app4/gen_translate_test.py` | 翻译规则校验（抽出规则表用 javac 17 实跑） |
