# BotCtl — ZenithProxy 手机控制台

用手机远程管理一台跑在 Linux 服务器上的 [ZenithProxy](https://github.com/rfresh2/ZenithProxy) 机器人。

包含三部分：

| 部分 | 说明 |
| --- | --- |
| **Android App** | 纯 Java、无第三方依赖、无 Gradle 的 7 页控制台，直接吃面板的 HTTP API |
| **服务端脚本** | 一键部署（Java 21 + 机器人 + 面板插件 + 地图服务 + systemd 自启） |
| **地图服务** | 索引生成器 + 删图接口，让 App 能看／删机器人渲染出来的地图 |

> 本项目**只做客户端与部署脚本**，机器人本体是上游的 ZenithProxy（AGPL-3.0），
> 不随仓库分发，由部署脚本按需下载。

---

## 功能

**状态页**：在线／离线、账号、服务器、**实时 via 版本**、血量、饱食度、延迟波动曲线（自绘）、坐标／维度、在线时长、主机 CPU／内存／网速；一键上线／下线；坐标与 IP 可一键隐藏（截图防泄露）。

**白名单页**：增删查、启用状态、踢出提示语。

**日志页**：SSE 实时日志，带 `《(账号)》` 前缀和时间戳，全中文；六类分拣
（个人死亡／服务器死亡／附近玩家／聊天／系统／全部）；附近玩家事件会从多行碎片拼成一行并丢掉 UUID：

```
《(YourMainName)》 19:05:02 【附近玩家】 有玩家进入视野 / 玩家名 / ||SomePlayer / 玩家坐标[208, 73, 203]
```

**功能页**：目标服务器切换与本地列表、9 个自动化模块开关（带实时状态徽标）、
Baritone 寻路（前往／跟随／挖矿／停止）、信息查询（统计／时长／背包／Tab／准星／路径点／队列／任务）、
聊天发送、**任意命令输入框**（万用入口）。

**版本页**：ViaVersion 跨版本控制 —— 开关、**版本列表点选**（点一行即切换，选中行变绿）、重连生效。

**地图页**：生成周围地图、刷新、**单张删除／清空全部**、缩略图放大。

**账号页**：面板凭据、游戏账号切换（正版走设备码，离线走 `unsupported` 离线认证）。

---

## 架构与端口

```
┌─────────────┐   HTTP :8080  (面板 API + 静态地图)
│  Android    │──────────────────────────────┐
│  BotCtl     │   HTTP :8081  (删图服务)      │
└─────────────┘──────────────────────────┐   │
                                         ▼   ▼
                              ┌──────────────────────┐
                              │ 服务器                │
                              │  ZenithProxy.jar      │  ← 机器人本体
                              │  ├ plugins/WebUI.jar  │  ← 面板 API
                              │  ├ config.json        │
                              │  └ maps/*.png         │
                              │  mapindex.py (timer)  │  ← 地图索引
                              │  mapapi.py  (:8081)   │  ← 删图
                              └──────────┬───────────┘
                                         │ MC 协议
                                         ▼
                                   目标 MC 服务器
```

| 端口 | 用途 | App 必需 |
| --- | --- | --- |
| 8080 | 面板 API + 地图静态文件 | ✅ |
| 8081 | 地图删除 | 仅删图 |
| 25565 | 玩家连代理 | 仅进服 |

---

## 快速开始

### 1. 部署服务器

```bash
# 把仓库传到服务器
git clone <your-repo-url> && cd botctl

# 下载上游两个 jar（带 SHA256 校验）
bash scripts/fetch-upstream.sh

# 一键部署（默认目标服 2b2t.org，用 --server 改）
sudo bash server/deploy_zenith.sh --server mc.example.com
```

脚本会装 Java 21、建 swap、放 jar、写面板配置（随机密码）、配 systemd、关掉几个已知坑
（`authBypasser`、`verifyUsers`、`autoUpdate`），最后打印面板地址和密码。

详见 [docs/部署教程.md](docs/部署教程.md)。

### 2. 装 App

直接下 Release 里的 `BotCtl-x.y.apk`，或自己编（见下）。

### 3. 连上去

App「账号」页填 `服务器IP:8080` + 面板用户名密码 → 添加游戏账号 → 切换 → 走微软设备码 → 上线。

---

## 自己编译 APK

不需要 Gradle / Android Studio / AndroidX，只要有 JDK 17 和 Android build-tools：

```bash
# 1) 装工具链（Debian/Ubuntu）
sudo apt install -y openjdk-17-jdk unzip
curl -LO https://dl.google.com/android/repository/build-tools_r34-linux.zip
curl -LO https://dl.google.com/android/repository/platform-34-ext10_r01.zip
unzip -q build-tools_r34-linux.zip -d /opt/android
unzip -q platform-34-ext10_r01.zip -d /opt/android/pl
cp -r /opt/android/android-14 /opt/android/build-tools
mkdir -p /opt/android/platform-34 && cp /opt/android/pl/android-34-ext10/android.jar /opt/android/platform-34/

# 2) 编译
cd app && bash build_apk.sh          # 产出 BotCtl-<ver>.apk
```

> ⚠️ **必须用 JDK 17 的 javac**。JDK 21 的 javac 会给匿名类构造函数写入一个参数名为空的
> `MethodParameters` 属性，d8/R8 读它时直接 NPE（`Cannot invoke "String.length()" ...`），
> `-g:none`、`-parameters` 都压不住。JDK 17 不写这个属性，一次通过。

---

## 许可

| 内容 | 许可 |
| --- | --- |
| 本仓库代码（App 源码、部署脚本、地图服务） | **MIT**，见 [LICENSE](LICENSE) |
| ZenithProxy（机器人本体） | **AGPL-3.0** — 不随仓库分发，部署脚本运行时从上游下载 |
| ZenithProxy-WebUI（面板插件） | **AGPL-3.0-or-later** — 同上 |
| 地图 PNG / 渲染产物 | 由用户服务器生成，不含在本仓库 |

详见 [NOTICE](NOTICE)。

---

## 免责声明

- 仅供学习与自用。**在公共服务器上用机器人可能违反该服规则**，封号等后果自负。
- 自动战斗类功能（KillAura 等）尤其容易触发封禁，界面上已标红提示。
- 面板默认走明文 HTTP，请至少用云防火墙把 8080/8081 限制为可信来源 IP；
  公网长期暴露有被扫描的风险。
