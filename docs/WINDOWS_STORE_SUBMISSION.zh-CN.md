# Windows 版 Microsoft Store 打包

此命令为 Partner Center 中的 **HostTap** 产品打包。Windows 应用显示名为 **HostTap Remote**，包含 x64 和 arm64。它只在本机生成文件，不会上传。

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
  "displayName": "HostTap Remote",
  "version": "1.0.0.0"
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

# 构建 x64 + arm64 MSIX，并检查生成结果
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-msix.ps1
Get-Item .\artifacts\msix\packages\HostTap-Remote.msixupload
Get-Content .\artifacts\msix\packages\HostTap-Remote.msixupload.sha256
```

生成的 `artifacts\msix\packages\HostTap-Remote.msixupload` 才是之后在 Partner Center「包 / Packages」中使用的文件。不要上传单独的 `.msix` 或 `.msixbundle`。商店包无需本地签名；每次更新前提高 `version` 的四段版本号。
