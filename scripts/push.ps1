# 一条命令把本目录推到一个空仓库
#   .\scripts\push.ps1 -Remote https://github.com/you/botctl.git
#   .\scripts\push.ps1 -Remote git@github.com:you/botctl.git -Message "feat: 初始提交"
param(
    [Parameter(Mandatory = $true)][string]$Remote,
    [string]$Message = "feat: BotCtl —— ZenithProxy 手机控制台",
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Die($msg) { Write-Host "[失败] $msg" -ForegroundColor Red; exit 1 }
function Info($msg) { Write-Host "[信息] $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "[完成] $msg" -ForegroundColor Green }

# 0) 前置检查
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Die "没装 git。先装 Git for Windows: https://git-scm.com/download/win"
}
Info "git: $((git --version))"

# 1) 身份（没配全局就配本地，避免提交失败）
$n = git config --get user.name
$e = git config --get user.email
if (-not $n) { git config user.name "botctl" ; Info "已设置本地 user.name=botctl（可改）" }
if (-not $e) { git config user.email "botctl@users.noreply.github.com"; Info "已设置本地 user.email" }

# 2) 初始化
if (-not (Test-Path ".git")) {
    Info "初始化仓库"
    git init -b $Branch | Out-Null
} else {
    Info "已存在 .git，跳过 init"
}

# 3) 提交
git add -A
$staged = (git diff --cached --name-only | Measure-Object).Count
if ($staged -eq 0) {
    Info "没有新改动需要提交"
} else {
    Info "暂存 $staged 个文件"
    git commit -m $Message | Out-Null
    Ok "已提交"
}

# 4) remote
$existing = git remote get-url origin 2>$null
if ($existing) {
    if ($existing -ne $Remote) {
        Info "更新 origin: $existing -> $Remote"
        git remote set-url origin $Remote
    }
} else {
    git remote add origin $Remote
    Info "已添加 origin: $Remote"
}

# 5) 推送
Info "推送中（如提示登录，请输入用户名 + PAT，或先跑 gh auth login）"
git push -u origin $Branch
if ($LASTEXITCODE -ne 0) { Die "推送失败，看上面的报错；认证问题见 PUSH.md" }

Ok "已推送到 $Remote"
Write-Host ""
Write-Host "接下来建议：" -ForegroundColor Yellow
Write-Host "  1) 在仓库设置里填 Description / Topics / 确认 License 为 MIT"
Write-Host "  2) 打标签并发 Release 附上 APK："
Write-Host "       git tag v5.5; git push origin v5.5"
