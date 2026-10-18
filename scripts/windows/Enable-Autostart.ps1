$ErrorActionPreference = 'Stop'
$exe = Join-Path $PSScriptRoot 'SofaTap.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Keep this script next to SofaTap.exe.' }
# Use the receiver's Unicode Shell Link implementation and the same preferences.
$process = Start-Process -FilePath $exe -ArgumentList '--enable-autostart' -Wait -PassThru
if ($process.ExitCode -ne 0) { throw 'HostTap Remote could not configure startup. Check startup folder permissions and the existing SofaTap.lnk target.' }
Write-Output 'HostTap Remote will start after you sign in. Keep this folder in its current location.'
Write-Output 'If Windows Startup Apps has disabled HostTap Remote, enable it there. This script does not override that choice.'
