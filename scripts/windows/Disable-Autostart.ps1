$ErrorActionPreference = 'Stop'
$exe = Join-Path $PSScriptRoot 'SofaTap.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Keep this script next to SofaTap.exe.' }
$process = Start-Process -FilePath $exe -ArgumentList '--disable-autostart' -Wait -PassThru
if ($process.ExitCode -ne 0) { throw 'HostTap Remote could not disable startup. Check startup folder permissions and the existing SofaTap.lnk target.' }
Write-Output 'HostTap Remote automatic startup is disabled.'
