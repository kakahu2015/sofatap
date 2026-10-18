# Windows 版 Microsoft Store 打包

此命令为 Partner Center 中已保留名称 **HostTap** 的产品打包。包清单显示名使用 **HostTap**，生成 x64 和 arm64 的 Windows 桌面包。它只在本机生成文件，不会上传。

## 在 Windows PowerShell 执行

需要 Git for Windows、.NET 10 SDK 和包含 `makeappx.exe` 的 Windows SDK。缺少时先安装（已安装的可跳过），安装后重新打开 PowerShell：

```powershell
winget install --id Git.Git --exact --source winget
winget install --id Microsoft.DotNet.SDK.10 --exact --source winget
winget install --id Microsoft.WindowsSDK.10.0.28000 --exact --source winget
```

第一次运行会克隆仓库；已有仓库时会快进更新到 `main`。

```powershell
$repo = Join-Path $env:USERPROFILE 'source\sofatap'
if (-not (Test-Path $repo)) {
    git clone --branch main https://github.com/kakahu2015/sofatap.git $repo
}
Set-Location $repo
git switch main
git pull --ff-only origin main

# Partner Center > HostTap > Product identity 的实际值
$identityJson = @'
{
  "identityName": "GUIHUIHU.HostTap",
  "publisher": "CN=A4BEB966-7421-4DE4-83EB-CB29EEC65FCE",
  "publisherDisplayName": "GUIHUI HU",
  "displayName": "HostTap",
  "version": "1.0.0.1"
}
'@
$identityJson | Set-Content .\packaging\windows-store\store-identity.json -Encoding UTF8

# 确认 .NET 10 SDK 已安装
$sdks = dotnet --list-sdks
if (-not ($sdks -match '^10\.')) { throw '.NET 10 SDK 未安装或不在 PATH 中。' }

# 自动选取已安装 Windows SDK 中最新的 x64 makeappx.exe
$makeappx = Get-ChildItem -Path "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\makeappx.exe" -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Directory.Parent.Name } -Descending |
    Select-Object -First 1
if (-not $makeappx) { throw '找不到 makeappx.exe；请安装 Windows SDK 后重试。' }
$env:SOFATAP_WINDOWS_SDK_BIN = $makeappx.DirectoryName

# 构建 Windows.Desktop 的 x64 + arm64 MSIX，并检查生成结果
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-msix.ps1
Get-Item .\artifacts\msix\packages\HostTap-Remote.msixupload
Get-Content .\artifacts\msix\packages\HostTap-Remote.msixupload.sha256
```

## 上传到现有草稿

在 Partner Center 打开 **HostTap → 提交 1 → 程序包 / Packages**：

1. 选择 `artifacts\msix\packages\HostTap-Remote.msixupload`。文件名里的 `HostTap-Remote` 只是生成文件名；包清单中的商店显示名是已保留的 **HostTap**。
2. 设备系列只勾选 **Windows 10/11 Desktop**；取消 **Windows 10/11 Xbox**、Mobile、Team 和 Mixed Reality。此应用的清单目标是 `Windows.Desktop`，不支持 Xbox；包里含 x64 架构也不会把桌面应用变成 Xbox 应用。“Let Microsoft decide whether to make this app available to any future device families”是单独设置，保留当前值即可。
3. 如果草稿里还有旧的验证失败包，先删除旧包，再上传新生成的 `.msixupload`，等待 Partner Center 重新验证。不要提交认证，直到验证错误清零。

不要上传单独的 `.msix` 或 `.msixbundle`。Microsoft Store 包无需本地签名。这个重打包版本从先前已上传的 `1.0.0.0` 提升为 `1.0.0.1`；之后每次更新都提高四段版本号。
