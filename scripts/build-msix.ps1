#!/usr/bin/env pwsh
# Builds Microsoft Store MSIX packages (x64 and arm64) for the Windows receiver.
# Requires: the .NET 10 SDK, and makeappx.exe / signtool.exe from the Windows SDK.
# This machine resolves those tools from Microsoft.Windows.SDK.BuildTools under .tools.
#
# Outputs under artifacts/msix/packages: one .msix per architecture, a .msixbundle,
# and a .msixupload (the archive Partner Center accepts for a Win32 packaged app).
# Store packages are uploaded unsigned; Partner Center re-signs them. Set SOFATAP_STORE_PFX
# only for sideload testing, which requires the certificate in the local trusted store.
#
# Store submission identity and signing certificate are supplied through environment
# variables, because Partner Center assigns the final values:
#   SOFATAP_STORE_IDENTITY                  Package/Identity/Name      (default org.kakahu.hosttap)
#   SOFATAP_STORE_PUBLISHER                 Package/Identity/Publisher (default CN=org.kakahu.hosttap;
#                                           must be replaced by the Partner Center publisher ID)
#   SOFATAP_STORE_DISPLAY_NAME              Application display name   (default HostTap Remote)
#   SOFATAP_STORE_PUBLISHER_DISPLAY_NAME    Publisher display name     (default HostTap Remote)
#   SOFATAP_STORE_VERSION                   four-part MSIX version     (default from updates/desktop-version.txt)
#   SOFATAP_STORE_PFX / SOFATAP_STORE_PFX_PASSWORD   optional code-signing certificate
#   SOFATAP_MAKEAPPX / SOFATAP_SIGNTOOL / SOFATAP_MAKEPRI   optional explicit tool paths
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
Add-Type -AssemblyName System.Drawing

function Get-Setting([string]$name, [string]$default) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $default }
    return $value
}

# Partner Center values are normally kept in an untracked local file so they do not
# have to be exported in every shell. Environment variables still take precedence.
$identityFile = Join-Path $repo 'packaging/windows-store/store-identity.json'
$fileIdentity = $null
if (Test-Path -LiteralPath $identityFile -PathType Leaf) {
    $fileIdentity = Get-Content -LiteralPath $identityFile -Raw | ConvertFrom-Json
}
function Get-IdentityFileSetting([string]$name) {
    if ($null -eq $fileIdentity) { return $null }
    $property = $fileIdentity.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    $value = [string]$property.Value
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    return $value
}
function Resolve-StoreSetting([string]$environmentName, [string]$fileName, [string]$default) {
    $value = [Environment]::GetEnvironmentVariable($environmentName)
    if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    $value = Get-IdentityFileSetting $fileName
    if ($null -ne $value) { return $value }
    return $default
}
function Require-File([string]$path, [string]$description) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$description is missing: $path" }
    return $path
}

$dotnet = Get-Setting 'SOFATAP_DOTNET' (Join-Path $repo '.tools/dotnet/dotnet.exe')
if (-not (Test-Path -LiteralPath $dotnet)) { $dotnet = (Get-Command dotnet -ErrorAction Stop).Source }
$sdkBin = Get-Setting 'SOFATAP_WINDOWS_SDK_BIN' (Join-Path $repo '.tools/windows-sdk-buildtools/bin/10.0.26100.0/x64')
$makeappx = Require-File (Get-Setting 'SOFATAP_MAKEAPPX' (Join-Path $sdkBin 'makeappx.exe')) 'makeappx.exe'
$makepri = Get-Setting 'SOFATAP_MAKEPRI' (Join-Path $sdkBin 'makepri.exe')
$signtool = Get-Setting 'SOFATAP_SIGNTOOL' (Join-Path $sdkBin 'signtool.exe')

$identity = Resolve-StoreSetting 'SOFATAP_STORE_IDENTITY' 'identityName' 'org.kakahu.hosttap'
$publisher = Resolve-StoreSetting 'SOFATAP_STORE_PUBLISHER' 'publisher' 'CN=org.kakahu.hosttap'
$displayName = Resolve-StoreSetting 'SOFATAP_STORE_DISPLAY_NAME' 'displayName' 'HostTap Remote'
$publisherDisplayName = Resolve-StoreSetting 'SOFATAP_STORE_PUBLISHER_DISPLAY_NAME' 'publisherDisplayName' 'HostTap Remote'
$pfx = [Environment]::GetEnvironmentVariable('SOFATAP_STORE_PFX')
$pfxPassword = [Environment]::GetEnvironmentVariable('SOFATAP_STORE_PFX_PASSWORD')
if ($publisher -eq 'CN=org.kakahu.hosttap') {
    Write-Warning "Publisher is still the placeholder $publisher. Copy Package/Identity/Publisher from Partner Center into packaging/windows-store/store-identity.json; the Store rejects a publisher it did not issue."
}
if (-not (Test-Path -LiteralPath $identityFile) -and $publisher -ne 'CN=org.kakahu.hosttap') {
    Write-Warning "$identityFile does not exist; the build is using environment variables only."
}

$rawVersion = (Get-Content -LiteralPath (Join-Path $repo 'updates/desktop-version.txt') -Raw).Trim()
$msixVersion = Resolve-StoreSetting 'SOFATAP_STORE_VERSION' 'version' $(
    $numeric = ($rawVersion -split '[-+]')[0]
    $parts = $numeric.Split('.')
    while ($parts.Count -lt 3) { $parts += '0' }
    "$($parts[0]).$($parts[1]).$($parts[2]).0"
)
foreach ($part in $msixVersion.Split('.')) {
    $value = 0
    if (-not [int]::TryParse($part, [ref]$value) -or $value -lt 0 -or $value -gt 65535) {
        throw "SOFATAP_STORE_VERSION must be a four-part version with parts 0-65535: $msixVersion"
    }
}

$artifacts = Join-Path $repo 'artifacts/msix'
$packages = Join-Path $artifacts 'packages'
if (Test-Path -LiteralPath $artifacts) { Remove-Item -LiteralPath $artifacts -Recurse -Force }
New-Item -ItemType Directory -Path $packages -Force | Out-Null
$iconSource = Require-File (Join-Path $repo 'desktop/windows/Assets/AppIcon.png') 'MSIX icon source'

function New-Asset([string]$path, [int]$width, [int]$height, [string]$background, [double]$paddingRatio) {
    $image = [System.Drawing.Image]::FromFile($iconSource)
    try {
        $bitmap = New-Object System.Drawing.Bitmap($width, $height)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                if ([string]::IsNullOrEmpty($background)) {
                    $graphics.Clear([System.Drawing.Color]::Transparent)
                } else {
                    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml($background))
                }
                $padding = [int][Math]::Round([Math]::Min($width, $height) * $paddingRatio)
                $scale = [Math]::Min(($width - 2 * $padding) / $image.Width, ($height - 2 * $padding) / $image.Height)
                $drawWidth = [Math]::Max(1, [int][Math]::Round($image.Width * $scale))
                $drawHeight = [Math]::Max(1, [int][Math]::Round($image.Height * $scale))
                $graphics.DrawImage($image, [int](($width - $drawWidth) / 2), [int](($height - $drawHeight) / 2), $drawWidth, $drawHeight)
            } finally { $graphics.Dispose() }
            New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $bitmap.Dispose() }
    } finally { $image.Dispose() }
}

function New-Assets([string]$assetsDirectory) {
    New-Item -ItemType Directory -Path $assetsDirectory -Force | Out-Null
    New-Asset (Join-Path $assetsDirectory 'Square44x44Logo.png') 44 44 '' 0.08
    New-Asset (Join-Path $assetsDirectory 'Square150x150Logo.png') 150 150 '#11191B' 0.16
    New-Asset (Join-Path $assetsDirectory 'Square310x310Logo.png') 310 310 '#11191B' 0.20
    New-Asset (Join-Path $assetsDirectory 'Wide310x150Logo.png') 310 150 '#11191B' 0.16
    New-Asset (Join-Path $assetsDirectory 'StoreLogo.png') 50 50 '' 0.04
    New-Asset (Join-Path $assetsDirectory 'SplashScreen.png') 620 300 '#11191B' 0.34
}

function Write-Manifest([string]$path, [string]$architecture) {
    $manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
         xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
         xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
         IgnorableNamespaces="uap rescap">
  <Identity Name="$identity" Publisher="$publisher" Version="$msixVersion" ProcessorArchitecture="$architecture" />
  <Properties>
    <DisplayName>$displayName</DisplayName>
    <PublisherDisplayName>$publisherDisplayName</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-us" />
    <Resource Language="zh-cn" />
  </Resources>
  <Applications>
    <Application Id="App" Executable="hosttap.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements DisplayName="$displayName" Description="Pair your iPhone with this computer over the local network for mouse, keyboard and text input." BackgroundColor="#11191B" Square150x150Logo="Assets\Square150x150Logo.png" Square44x44Logo="Assets\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="Assets\Wide310x150Logo.png" Square310x310Logo="Assets\Square310x310Logo.png" ShortName="HostTap" />
        <uap:SplashScreen Image="Assets\SplashScreen.png" BackgroundColor="#11191B" />
      </uap:VisualElements>
    </Application>
  </Applications>
  <Capabilities>
    <Capability Name="internetClient" />
    <Capability Name="privateNetworkClientServer" />
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
"@
    [System.IO.File]::WriteAllText($path, $manifest, [System.Text.UTF8Encoding]::new($false))
}

function New-Package([string]$architecture) {
    $runtime = "win-$architecture"
    $work = Join-Path $artifacts "work/$architecture"
    $layout = Join-Path $work 'layout'
    $backendOut = Join-Path $work 'backend'
    $frontendOut = Join-Path $work 'frontend'
    New-Item -ItemType Directory -Path $layout, $backendOut, $frontendOut -Force | Out-Null

    Write-Host "Publishing $runtime backend and hosttap.exe..."
    & $dotnet publish (Join-Path $repo 'host/SofaTap.Host.csproj') -c Release -r $runtime `
        --artifacts-path (Join-Path $work 'backend-intermediate') --self-contained true `
        -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:PublishTrimmed=false `
        -p:DebugType=None -p:DebugSymbols=false -o $backendOut | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Backend publish failed for $runtime ($LASTEXITCODE)." }
    & $dotnet publish (Join-Path $repo 'desktop/windows/SofaTap.Desktop.csproj') -c Release -r $runtime `
        --artifacts-path (Join-Path $work 'frontend-intermediate') --self-contained true `
        -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:PublishTrimmed=false `
        -p:DebugType=None -p:DebugSymbols=false -p:SofatapMsix=true -o $frontendOut | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Frontend publish failed for $runtime ($LASTEXITCODE)." }

    # Packaged executable name required for the Store package.
    Copy-Item -LiteralPath (Join-Path $frontendOut 'SofaTap.exe') -Destination (Join-Path $layout 'hosttap.exe')
    foreach ($file in Get-ChildItem -LiteralPath $backendOut -File) {
        $name = if ($file.Name -eq 'SofaTap.exe') { 'SofaTapHost.exe' } else { $file.Name }
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $layout $name)
    }
    foreach ($name in 'LICENSE', 'THIRD-PARTY-NOTICES.md') {
        Copy-Item -LiteralPath (Join-Path $repo $name) -Destination (Join-Path $layout $name)
    }
    New-Assets (Join-Path $layout 'Assets')
    Write-Manifest (Join-Path $layout 'AppxManifest.xml') $architecture

    # resources.pri is required for the declared package languages.
    if (Test-Path -LiteralPath $makepri) {
        $config = Join-Path $work 'priconfig.xml'
        & $makepri createconfig /cf $config /dq en-US /o | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "makepri createconfig failed for $architecture." }
        & $makepri new /pr $layout /cf $config /of (Join-Path $layout 'resources.pri') /o | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "makepri new failed for $architecture." }
    }

    $package = Join-Path $packages "HostTap-Remote-$architecture.msix"
    & $makeappx pack /o /d $layout /p $package | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "makeappx pack failed for $architecture." }

    $verify = Join-Path $work 'unpacked'
    & $makeappx unpack /o /p $package /d $verify | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "makeappx unpack verification failed for $architecture." }
    foreach ($required in 'AppxManifest.xml', 'hosttap.exe', 'SofaTapHost.exe', 'Assets\Square150x150Logo.png') {
        if (-not (Test-Path -LiteralPath (Join-Path $verify $required))) { throw "Package verification is missing $required for $architecture." }
    }
    Write-Host "Built $package"
    return $package
}

$built = @{}
foreach ($architecture in 'x64', 'arm64') {
    $built[$architecture] = New-Package $architecture
}

# A bundle lets one Store submission serve both architectures.
$bundleDirectory = Join-Path $artifacts 'bundle'
foreach ($architecture in 'x64', 'arm64') {
    $directory = Join-Path $bundleDirectory $architecture
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Copy-Item -LiteralPath $built[$architecture] -Destination $directory
}
$bundle = Join-Path $packages 'HostTap-Remote.msixbundle'
& $makeappx bundle /o /d $bundleDirectory /p $bundle | Out-Host
if ($LASTEXITCODE -ne 0) { throw "makeappx bundle failed." }

# Partner Center takes a ZIP of the bundle as an .msixupload for Win32 packaged apps.
# Compress-Archive appends .zip unless the destination already ends with .zip, so build
# the archive under that name and then move it to the submitted extension.
$uploadZip = Join-Path $packages 'HostTap-Remote.msixupload.zip'
$upload = Join-Path $packages 'HostTap-Remote.msixupload'
Remove-Item -LiteralPath $uploadZip, $upload -Force -ErrorAction SilentlyContinue
Compress-Archive -LiteralPath $bundle -DestinationPath $uploadZip -CompressionLevel Optimal
Move-Item -LiteralPath $uploadZip -Destination $upload -Force
if (-not (Test-Path -LiteralPath $upload)) { throw ".msixupload creation failed." }

$outputs = @($built['x64'], $built['arm64'], $bundle, $upload)
if (-not [string]::IsNullOrWhiteSpace($pfx)) {
    $certificate = Require-File $pfx 'SOFATAP_STORE_PFX'
    # The unsigned archives are uploaded as-is; only the installable artifacts carry a signature.
    foreach ($package in @($built['x64'], $built['arm64'], $bundle)) {
        $arguments = @('sign', '/fd', 'SHA256', '/f', $certificate)
        if (-not [string]::IsNullOrWhiteSpace($pfxPassword)) { $arguments += @('/p', $pfxPassword) }
        $arguments += @('/tr', 'http://timestamp.digicert.com', '/td', 'SHA256', $package)
        & $signTool $arguments
        if ($LASTEXITCODE -ne 0) { throw "signtool sign failed: $package" }
    }
    Write-Host "Signed packages with $certificate."
}

Write-Host ""
Write-Host "MSIX identity : $identity"
Write-Host "Publisher     : $publisher"
Write-Host "Version       : $msixVersion"
foreach ($package in $outputs) {
    $hash = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = [math]::Round((Get-Item -LiteralPath $package).Length / 1MB, 1)
    Write-Host ("{0}  ({1} MB)  sha256={2}" -f $package, $size, $hash)
    [System.IO.File]::WriteAllText("$package.sha256", "$hash  $([System.IO.Path]::GetFileName($package))`n", [System.Text.UTF8Encoding]::new($false))
}
