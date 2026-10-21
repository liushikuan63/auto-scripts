<#
.SYNOPSIS
  代码健康审查：检查注释与代码是否脱节，以及是否存在零引用的死代码。

.DESCRIPTION
  规则（全部为硬失败，命中即退出码 1）：
    R1 孤儿 KDoc      —— 注释块后面没有跟声明（注释没有对应物）
    R2 失效分段标记   —— `// ==== xxx ====` 后面没有声明/注释（标记悬空）
    R3 零引用死代码   —— private/internal 函数在全仓没有调用点
    R4 缺类级 KDoc    —— 顶层类/对象/接口没有类级说明

  另有一条信息性统计（不判失败）：R5 公开成员中无注释的数量。

  跨平台：Windows 与 Linux 的 GitHub Actions 均可直接运行（仅用 pwsh 内置能力）。

.EXAMPLE
  ./scripts/audit-code-health.ps1
  ./scripts/audit-code-health.ps1 -Root D:\path\to\repo
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'

$srcRoot = Join-Path $Root 'app/src/main/java'
if (-not (Test-Path $srcRoot)) {
    Write-Output "AUDIT_SKIP: 未找到源码目录 $srcRoot"
    exit 2
}

function Get-RelPath([string]$p) {
    return $p.Substring($Root.Length).TrimStart([char]'\', [char]'/')
}

# 声明判定：注释块/标记后面必须跟这些之一，才算「有对应物」
function Test-IsDeclaration([string]$line) {
    $t = $line.Trim()
    if ($t -eq '') { return $false }
    if ($t.StartsWith('//') -or $t.StartsWith('/*') -or $t.StartsWith('*')) { return $false }
    if ($t -eq 'package com.farmbot' -or $t -like 'package *') { return $true }
    return $t -match '^(private |internal |public |protected |open |abstract |override |suspend |inline |operator |annotation |data |sealed |enum |value |companion |const |lateinit |expect |actual )*(fun|val|var|class|interface|object|typealias|constructor|init|@)'
}

$ktFiles = @(Get-ChildItem $srcRoot -Recurse -Filter *.kt -File |
    Where-Object { $_.FullName -notmatch '[\\/]org[\\/]opencv[\\/]' })   # 排除仓库内自带的 OpenCV API 桩
if ($ktFiles.Count -eq 0) { Write-Output 'AUDIT_SKIP: 没有 Kotlin 源文件'; exit 2 }

# 引用语料：所有 Kotlin + 资源与规则文件，避免把反射/资源引用误判为死代码
$corpusFiles = @(Get-ChildItem (Join-Path $Root 'app/src') -Recurse -File |
    Where-Object { $_.Extension -in @('.kt', '.java', '.xml') })
$proGuard = Join-Path $Root 'app/proguard-rules.pro'
if (Test-Path $proGuard) { $corpusFiles += Get-Item $proGuard }
$corpus = ($corpusFiles | ForEach-Object { [System.IO.File]::ReadAllText($_.FullName) }) -join "`n"

$findings = New-Object System.Collections.Generic.List[string]
$stats = @{ R1 = 0; R2 = 0; R3 = 0; R4 = 0; R5 = 0 }

foreach ($f in $ktFiles) {
    $rel = Get-RelPath $f.FullName
    $L = [System.IO.File]::ReadAllLines($f.FullName)

    # ---- R1 / R2 / R4 共用一次扫描 ----
    for ($i = 0; $i -lt $L.Count; $i++) {
        $t = $L[$i].Trim()

        # R1：孤儿 KDoc
        if ($t.StartsWith('/**')) {
            $s = $i
            while ($i -lt $L.Count -and -not $L[$i].Trim().EndsWith('*/')) { $i++ }
            $j = $i + 1
            while ($j -lt $L.Count -and $L[$j].Trim() -eq '') { $j++ }
            $next = if ($j -ge $L.Count) { '' } else { $L[$j] }
            if ($j -ge $L.Count -or -not (Test-IsDeclaration $next)) {
                $stats.R1++
                $findings.Add("R1 孤儿 KDoc   ${rel}:$($s + 1)  → 后面没有声明" + $(if ($j -ge $L.Count) { '（文件结束）' } else { "，而是 '$($next.Trim())'" }))
            }
            continue
        }

        # R2：失效分段标记 —— 只有当标记后面直接是收尾大括号或文件结束时才算失效；
        # 后面接语句（if / for / 调用）是合法的「引出一段逻辑」
        if ($t -match '^//\s*=+\s*(.+?)\s*=+\s*$' -and $t -notmatch '^//\s*=+\s*$') {
            $j = $i + 1
            while ($j -lt $L.Count -and $L[$j].Trim() -eq '') { $j++ }
            $next = if ($j -ge $L.Count) { '' } else { $L[$j].Trim() }
            if ($j -ge $L.Count -or $next -eq '}') {
                $stats.R2++
                $findings.Add("R2 失效标记   ${rel}:$($i + 1)  「$($Matches[1])」之后没有任何内容")
            }
            continue
        }

        # R4：顶层类/对象/接口缺类级 KDoc
        if ($t -match '^(internal |public |private |open |abstract |sealed |data |enum |value )*(class|interface|object|enum class)\s' -and $L[$i] -notmatch '^\s') {
            $k = $i - 1
            while ($k -ge 0 -and $L[$k].Trim() -eq '') { $k-- }
            $hasDoc = ($k -ge 0 -and $L[$k].Trim().EndsWith('*/'))
            if (-not $hasDoc) {
                $stats.R4++
                $findings.Add("R4 缺类级KDoc ${rel}:$($i + 1)  $($L[$i].Trim())")
            }
        }
    }

    # ---- R3：零引用死代码 ----
    for ($i = 0; $i -lt $L.Count; $i++) {
        $line = $L[$i]
        if ($line -notmatch '^\s*(private |internal )\s*(suspend )?(inline )?fun\s+([A-Za-z_][A-Za-z0-9_]*)') { continue }
        $name = $Matches[4]          # 先取名字：后续的 -notmatch 会覆盖 $Matches
        # 扩展函数：fun <接收者>.<名字>(…) —— 名字取最后一段，否则会把接收者误当函数名
        $rest = $line -replace '^\s*(private |internal )\s*(suspend )?(inline )?fun\s+', ''
        if ($rest -match '^[A-Za-z_][\w.]*\.([A-Za-z_]\w*)\s*\(') { $name = $Matches[1] }
        if ($line -match '\boverride\b') { continue }
        # 框架回调与 Compose 注解函数不算死代码
        $ctx = ''
        for ($b = [Math]::Max(0, $i - 4); $b -lt $i; $b++) { $ctx += $L[$b] }
        if ($ctx -match '@Composable|@Preview|@Test') { continue }
        # 调用点允许带接收者（ocr.tryClickTexts( / movement.walkNearText( …），
        # 也允许尾随 lambda 形式（captureCurrentBitmap { … }）
        $hits = ([regex]::Matches($corpus, ('(?<![\w])' + [regex]::Escape($name) + '\s*(\(|\{)'))).Count
        if ($hits -le 1) {
            $stats.R3++
            $findings.Add("R3 零引用死代码 ${rel}:$($i + 1)  $name")
        }
    }
}

# ---- R5（信息性）：公开成员无注释数 ----
foreach ($f in $ktFiles) {
    $L = [System.IO.File]::ReadAllLines($f.FullName)
    for ($i = 0; $i -lt $L.Count; $i++) {
        $t = $L[$i]
        if ($t -notmatch '^\s{0,4}(public |internal |override |suspend |inline |const |lateinit |@Volatile )*(fun|val|var)\s') { continue }
        if ($t -match '^\s{4,}') { continue }   # 更深缩进 = 局部变量
        $k = $i - 1
        while ($k -ge 0 -and $L[$k].Trim() -eq '') { $k-- }
        $hasDoc = ($k -ge 0 -and ($L[$k].Trim().EndsWith('*/') -or $L[$k].TrimStart().StartsWith('//')))
        if (-not $hasDoc -and $t -notmatch '//') { $stats.R5++ }
    }
}

Write-Output '=== 代码健康审查 ==='
Write-Output ("R1 孤儿 KDoc      : {0}" -f $stats.R1)
Write-Output ("R2 失效分段标记   : {0}" -f $stats.R2)
Write-Output ("R3 零引用死代码   : {0}" -f $stats.R3)
Write-Output ("R4 缺类级 KDoc    : {0}" -f $stats.R4)
Write-Output ("R5 公开成员无注释: {0}（信息性，不判失败）" -f $stats.R5)
Write-Output ''

$hard = $stats.R1 + $stats.R2 + $stats.R3 + $stats.R4
if ($hard -gt 0) {
    Write-Output '--- 明细 ---'
    $findings | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $hard)
    exit 1
}
Write-Output 'AUDIT_PASS: 无孤儿注释 / 无失效标记 / 无零引用死代码 / 无缺类级说明'
exit 0
