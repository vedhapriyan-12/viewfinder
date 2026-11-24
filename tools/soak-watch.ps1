# Independent observer for the on-device soak test (E4). Runs on the PC, talks to the phone over adb.
# Usage: powershell -File soak-watch.ps1 -Device a15d4524 -Minutes 65 -Out <csv path>
param(
    [Parameter(Mandatory)] [string] $Device,
    [int] $Minutes = 65,
    [Parameter(Mandatory)] [string] $Out,
    [string] $Package = "app.viewfinder.spike"
)

$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$end = (Get-Date).AddMinutes($Minutes)
"time,elapsed_s,foreground,app_pid,batt_pct,batt_temp_c,thermal_status,charging" | Set-Content $Out -Encoding utf8
$t0 = Get-Date

while ((Get-Date) -lt $end) {
    $now = Get-Date
    $focus = (& $adb -s $Device shell dumpsys window 2>$null | Select-String "mCurrentFocus" | Select-Object -First 1).Line
    $fg = if ($focus -match [regex]::Escape($Package)) { "app" } elseif ($focus) { "OTHER" } else { "unknown" }
    $pid_ = (& $adb -s $Device shell pidof $Package 2>$null | Out-String).Trim()
    $batt = & $adb -s $Device shell dumpsys battery 2>$null | Out-String
    $pct = if ($batt -match "level:\s*(\d+)") { $Matches[1] } else { "" }
    $temp = if ($batt -match "temperature:\s*(\d+)") { [math]::Round([int]$Matches[1] / 10, 1) } else { "" }
    $chg = if ($batt -match "USB powered:\s*true|AC powered:\s*true") { "yes" } else { "no" }
    $th = & $adb -s $Device shell dumpsys thermalservice 2>$null | Out-String
    $ts = if ($th -match "Thermal Status:\s*(\d+)") { $Matches[1] } else { "" }
    "{0},{1},{2},{3},{4},{5},{6},{7}" -f $now.ToString("HH:mm:ss"), [int]($now - $t0).TotalSeconds, $fg, $pid_, $pct, $temp, $ts, $chg |
        Add-Content $Out -Encoding utf8
    Start-Sleep -Seconds 30
}
