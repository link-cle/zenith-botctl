# 怎么把它推到你的 GitHub

## 你需要准备什么

| # | 需要 | 说明 |
| --- | --- | --- |
| 1 | GitHub 账号 | — |
| 2 | **一个空的仓库** | 建仓库时**不要**勾 "Add a README"、"Add .gitignore"、"Choose a license"，否则第一次 push 会冲突 |
| 3 | 本机装 **Git** | Windows 用 [Git for Windows](https://git-scm.com/download/win)（装完带 `git` 和 `git bash`） |
| 4 | 认证方式 | 三选一：**GitHub CLI 登录**（最省事）／**PAT**（https）／**SSH key** |

> 本仓库自带 `LICENSE`、`.gitignore`、`README.md`，所以第 2 步一定要建**空**仓库。

---

## 方式 A：一条命令（推荐）

装好 Git 后，在本目录执行：

```powershell
# PowerShell（Windows）
.\scripts\push.ps1 -Remote https://github.com/<你的用户名>/botctl.git
```

```bash
# Linux / macOS / git bash
bash scripts/push.sh https://github.com/<你的用户名>/botctl.git
```

脚本会：`git init -b main` → 配置本地身份（若全局没配）→ `git add` → 提交 → 加 remote → 推送。

想改提交信息：

```powershell
.\scripts\push.ps1 -Remote <url> -Message "feat: 初始版本"
```

---

## 方式 B：手动敲（等价）

```bash
cd botctl-repo
git init -b main
git config user.name  "你的名字"
git config user.email "你的邮箱"
git add .
git commit -m "feat: BotCtl —— ZenithProxy 手机控制台"
git remote add origin https://github.com/<你的用户名>/botctl.git
git push -u origin main
```

---

## 认证怎么过

### 选项 1：GitHub CLI（最省事，推荐）

```bash
winget install GitHub.cli      # Windows
gh auth login                  # 选 GitHub.com → HTTPS → 浏览器授权
gh auth setup-git              # 让 git 复用 gh 的凭据
```

之后 `git push` 不再问密码。

### 选项 2：Personal Access Token（PAT）

1. GitHub → Settings → Developer settings → **Personal access tokens → Fine-grained tokens**
2. Repository access 选刚建的那个仓库
3. Permissions 只要 **Contents: Read and write**
4. 复制 token（只显示一次）
5. push 时用户名填你的 GitHub 用户名，**密码填 token**

不想每次输：

```bash
git config --global credential.helper manager   # Windows 会弹窗保存
```

> ⚠️ 不要用账号密码，GitHub 已经不支持；也不要把 token 写进 URL 后提交给别人。

### 选项 3：SSH key

```bash
ssh-keygen -t ed25519 -C "你的邮箱"
cat ~/.ssh/id_ed25519.pub      # 贴到 GitHub → Settings → SSH and GPG keys
git remote add origin git@github.com:<用户名>/botctl.git
```

**如果 443 被墙/不通**，用 SSH over 443：

```bash
# ~/.ssh/config
Host github.com
  HostName ssh.github.com
  Port 443
  User git
```

---

## 推上去之后建议做的三件事

### 1. 填仓库信息

- **Description**：`ZenithProxy 手机控制台：Android App + 一键部署脚本`
- **Topics**：`minecraft` `zenithproxy` `android` `minecraft-bot` `self-hosted`
- 确认 License 识别为 MIT（`LICENSE` 已就位）

### 2. 发一个 Release

```bash
git tag v5.5
git push origin v5.5
```

然后把 `releases/BotCtl-5.5.apk` 作为附件传到这个 Release。
**不要让用户去下仓库里的 APK**：Release 附件更清晰，也方便统计下载。

> 我已经放了 `.github/workflows/build-apk.yml`：打 `v*` 标签时会自动构建 APK 并作为
> 想启用：把它复制到 `.github/workflows/build-apk.yml`（网页 New file 粘贴即可，或 token 加 workflow scope 后由脚本推）。
> 想产出**和线上同一签名**的包，把 keystore 转成 base64 存进 Secrets：
> `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEYSTORE_ALIAS`；不设置则每次生成临时密钥（产物可装，但与旧版签名不同，升级要先卸载）。

### 3. 打开 Actions

首次跑 workflow 需要在 Actions 页点一下启用（fork/新仓库常见）。

---

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `failed to push some refs` / `non-fast-forward` | 建仓库时勾了 README。`git pull --rebase origin main` 再 push，或删仓库重建空的 |
| `remote: Support for password authentication was removed` | 用了账号密码，改用 PAT 或 gh |
| `Failed to connect to github.com port 443` | 网络问题。用 SSH over 443，或挂代理：`git config --global http.proxy http://127.0.0.1:7890` |
| `fatal: not a git repository` | 没在 `botctl-repo` 目录里；先 `cd` 进去 |
| 提交里出现了 jar / 日志 | `.gitignore` 已覆盖；若已误提交：`git rm --cached <file>` 再提交 |
| `gh: command not found` | 没装 GitHub CLI；用 PAT 方式代替 |

---

## 关于许可（重要）

- 仓库里**不含** ZenithProxy 与 WebUI 的 jar，靠 `scripts/fetch-upstream.sh` 下载，
  所以仓库本身只有你的 **MIT** 代码。
- 如果你想把 jar 一起提交，请先读 `NOTICE` 里 AGPL 的合规要求；更推荐放 Release 附件。
- `LICENSE` 里的 `Copyright (c) 2026 BotCtl contributors` 可以改成你的名字/ID。
