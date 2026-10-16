<#
.SYNOPSIS
  真机复跑日志判定：抓取或复判 FarmBot 状态机日志，按状态环与异常日志给出通过/失败结论。

.DESCRIPTION
  判定规则全部取自代码里真实存在的日志文案：
    - 失败：未检测到录屏截图通道 / 状态超时，进入恢复流程 / 达到最大恢复次数，进入空闲 / 异常:
    - 通过：出现完整状态环（导航至农场 → 扫描作物 → (浇水中|收获中) → 售卖/存储 → 拜访好友 → 每日对战 → 升级决策 → 空闲）
  离线复判：-LogFile <路径> 直接判定已有日志（可在没有设备时自测脚本本身）。

.EXAMPLE
  .\verify-device-run.ps1 -LogFile .\out\sample.log
  .\verify-device-run.ps1 -Seconds 900
#>
[CmdletBinding()]
param(
    [string]$LogFile,
    [int]$Seconds = 600,
    [string]$Adb = 'D:\Android\Sdk\platform-tools\adb.exe',
    [string]$OutDir = "$PSScriptRoot\out"
)

$ErrorActionPreference = 'Stop'
$tag = 'FarmStateMachine'

$failPatterns = [ordered]@{
    '录屏通道未注入'   = '未检测到录屏截图通道'
    '状态超时'         = '状态超时，进入恢复流程'
    '恢复次数耗尽'     = '达到最大恢复次数，进入空闲'
    '执行异常'         = '异常:'
}
$ringOrder = @('导航至农场', '扫描作物', '浇水中|收获中', '售卖/存储', '拜访好友', '每日对战', '升级决策', '空闲')

if (-not $LogFile) {
    if (-not (Test-Path $Adb)) { Write-Output "ADB_NOT_FOUND: $Adb"; exit 3 }
    $devices = & $Adb devices | Select-String -Pattern '\tdevice$'
    if (($devices | Measure-Object).Count -eq 0) { Write-Output 'NO_DEVICE: 未检测到已授权设备'; exit 3 }
    if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
    $LogFile = Join-Path $OutDir ("farmbot-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
    Write-Output "抓取 $Seconds 秒 logcat（tag=$tag）→ $LogFile"
    & $Adb logcat -c
    $proc = Start-Process -FilePath $Adb -ArgumentList @('logcat', '-s', "$tag`:V") -RedirectStandardOutput $LogFile -NoNewWindow -PassThru
    Start-Sleep -Seconds $Seconds
    if (-not $proc.HasExited) { $proc.Kill() }
}

if (-not (Test-Path $LogFile)) { Write-Output "LOG_NOT_FOUND: $LogFile"; exit 3 }
$lines = [System.IO.File]::ReadAllLines($LogFile)
Write-Output ("日志行数 = " + $lines.Count + "  来源 = $LogFile")

$failures = @()
foreach ($k in $failPatterns.Keys) {
    $hit = @($lines | Where-Object { $_ -like ('*' + $failPatterns[$k] + '*') })
    if ($hit.Count -gt 0) { $failures += "$k x$($hit.Count)  例: $($hit[0].Trim())" }
}

# 状态转换序列（真实日志格式：状态转换: A → B）
$transitions = @()
foreach ($l in $lines) {
    if ($l -match '状态转换:\s*(.+?)\s*→\s*(.+?)\s*$') { $transitions += [pscustomobject]@{ From = $Matches[1]; To = $Matches[2] } }
}
$visited = $transitions | ForEach-Object { $_.To }
$covered = @()
foreach ($step in $ringOrder) {
    if (@($visited | Where-Object { $_ -match $step }).Count -gt 0) { $covered += $step }
}
$missed = @($ringOrder | Where-Object { $covered -notcontains $_ })

$clickCount = @($lines | Where-Object { $_ -like '*✓ 已点击*' }).Count
$retryCount = @($lines | Where-Object { $_ -like '*重试 (*' }).Count
Write-Output ""
Write-Output ("状态转换次数 = {0} ；点击数 = {1} ；重试次数 = {2}" -f $transitions.Count, $clickCount, $retryCount)
Write-Output ("状态环覆盖 {0}/{1} ；未覆盖：{2}" -f $covered.Count, $ringOrder.Count, ($missed -join ', '))

Write-Output ""
if ($failures.Count -gt 0) {
    Write-Output '判定：失败'
    $failures | ForEach-Object { Write-Output ("  - " + $_) }
    exit 2
}
if ($missed.Count -gt 0) {
    Write-Output '判定：部分通过（无失败日志，但状态环未跑满）'
    Write-Output ("  未覆盖：" + ($missed -join ', '))
    exit 1
}
Write-Output '判定：通过（状态环完整，且无失败日志）'
exit 0
