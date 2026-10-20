# Builds SBL and installs it on a TV over ADB.
#   .\install.ps1 -TvHost 192.168.1.50
# Needs JDK 17+, the Android SDK (ANDROID_HOME) and adb on PATH or in the SDK's platform-tools.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
if (-not (Test-Path 'local.properties')) { "sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Out-File -Encoding ascii local.properties }

if (-not $SkipBuild) { & .\gradlew.bat assembleRelease -q --console=plain; if ($LASTEXITCODE) { throw 'build failed' } }

$apk = 'app\build\outputs\apk\release\SlightlyBetterLauncher.apk'
$target = "${TvHost}:5555"
& $adb connect $target
& $adb -s $target install -r $apk
if ($LASTEXITCODE) { throw 'install failed' }
# Watch Next rows and backups/themes on the TV's storage need these two; both can also be granted in SBL settings.
& $adb -s $target shell pm grant com.bbu.sbl android.permission.READ_TV_LISTINGS
& $adb -s $target shell appops set com.bbu.sbl MANAGE_EXTERNAL_STORAGE allow
& $adb -s $target shell am start -n com.bbu.sbl/.MainActivity
Write-Host 'Installed. To make SBL the Home screen, press Home on the remote and choose it, or see README.'
