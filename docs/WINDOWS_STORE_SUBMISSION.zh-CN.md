# Windows 版 Microsoft Store 打包与提交

Windows 商店包名为 **HostTap Remote**，包含 x64 和 arm64。商店版无需本地签名；由 Microsoft 在认证时签名。

## 1. 准备

在 Windows 电脑检出仓库，并安装 **.NET 10 SDK** 和包含 `makeappx.exe` 的 **Windows SDK**。

从 Partner Center 当前产品的「产品标识 / Product identity」复制以下三项，必须原样复制：

- Package/Identity/Name
- Package/Identity/Publisher
- Publisher display name

首次配置时复制示例文件并编辑本地配置：

```powershell
Copy-Item packaging\windows-store\store-identity.json.example packaging\windows-store\store-identity.json
notepad packaging\windows-store\store-identity.json
```

填写 `identityName`、`publisher`、`publisherDisplayName`；`displayName` 保持 `HostTap Remote`。首次版本使用 `1.0.0.0`；后续提交提高版本号。配置文件已被 Git 忽略，不要提交。

## 2. 构建

在仓库根目录的 PowerShell 中运行：

```powershell
$env:SOFATAP_WINDOWS_SDK_BIN = 'C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64'
Test-Path "$env:SOFATAP_WINDOWS_SDK_BIN\makeappx.exe"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-msix.ps1
```

若 `Test-Path` 返回 `False`，把 `SOFATAP_WINDOWS_SDK_BIN` 改为本机实际包含 `makeappx.exe` 的 SDK 目录，再运行构建命令。构建脚本会检查 x64 和 arm64 包，并输出 SHA-256；缺少或填错 Partner Center 身份时不要上传。

## 3. 上传

只上传这个文件：

```text
artifacts\msix\packages\HostTap-Remote.msixupload
```

在 Partner Center 的提交草稿中打开「包 / Packages」上传它，并确认页面识别出 **x64**、**arm64** 和 Windows 桌面。完成商店列表的必填项后，再按需提交认证。不要单独上传 `.msix` 或 `.msixbundle` 文件；不要为商店包配置本地签名证书。
