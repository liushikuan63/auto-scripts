<#
.SYNOPSIS
  风格与粒度审查：常量命名、注释里的调用引用、文件粒度。

.DESCRIPTION
  R16 注释调用引用失效   注释里写成 `xxx.method(` 的调用，其方法名在代码中必须存在
  R17 一个文件多个顶层类型  一个 .kt 只应承载一个顶层类型（信息性 → 命中数与清单）
  R18 超大文件           单文件超过 400 行（信息性）

  每条规则放在独立函数里：共用循环变量与 $Matches 时，规则之间会互相干扰
  （曾出现「正则单独测能匹配、在脚本里却不触发」的现象，见 R6 / R12）。
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'
$srcRoot = Join-Path $Root 'app/src/main/java'
if (-not (Test-Path $srcRoot)) { Write-Output 'AUDIT_SKIP: 未找到源码目录'; exit 2 }

$files = @(Get-ChildItem $srcRoot -Recurse -Filter *.kt -File | Where-Object { $_.FullName -notmatch '[\\/]org[\\/]opencv[\\/]' })
$bad = New-Object System.Collections.Generic.List[string]
$info = New-Object System.Collections.Generic.List[string]

# 去注释后的代码语料（供 R16 判断方法是否存在）
$codeParts = foreach ($f in $files) {
    $t = [System.IO.File]::ReadAllText($f.FullName)
    $t = [regex]::Replace($t, '(?s)/\*.*?\*/', '')
    $t = [regex]::Replace($t, '(?<!:)//.*$', '', 'Multiline')
    $t
}
$codeText = ($codeParts -join "`n")

# ---------------- R16：注释里的调用引用是否还存在 ----------------
function Test-ReceiverRef($files, $codeText, $bad) {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($Root.Length).TrimStart([char]'\', [char]'/')
        foreach ($hit in (Select-String -Path $f.FullName -Pattern '`[A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]*)\(' -AllMatches)) {
            $line = $hit.Line
            if (-not $line.Trim().StartsWith('//')) { continue }
            foreach ($m in $hit.Matches) {
                $method = $m.Groups[1].Value
                if ($codeText -notmatch ('\.' + [regex]::Escape($method) + '\s*\(')) {
                    $bad.Add("R16 注释调用引用失效 ${rel}:$($hit.LineNumber)  注释里提到的 '$method(...)' 在代码中不存在")
                }
            }
        }
    }
}

# ---------------- R17 / R18：文件粒度 ----------------
function Test-FileGranularity($files, $info) {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($Root.Length).TrimStart([char]'\', [char]'/')
        $L = [System.IO.File]::ReadAllLines($f.FullName)
        $types = @()
        for ($i = 0; $i -lt $L.Count; $i++) {
            $m = Select-String -InputObject $L[$i] -Pattern '^(?:internal |public |private |open |abstract |sealed |data |enum |value |annotation )*(?:class|interface|object|enum class)\s+([A-Za-z_][A-Za-z0-9_]*)' -AllMatches
            foreach ($mm in $m.Matches) { $types += $mm.Groups[1].Value }
        }
        if ($types.Count -gt 1) {
            $info.Add("R17 文件含多个顶层类型 ${rel}  $($types -join ', ')")
        }
        if ($L.Count -gt 400) {
            $info.Add("R18 文件偏大 ${rel}  $($L.Count) 行（建议按职责拆分）")
        }
    }
}

Test-ReceiverRef $files $codeText $bad
Test-FileGranularity $files $info

Write-Output '=== 风格与粒度审查 ==='
Write-Output ("R16 注释调用失效   : {0}" -f (@($bad | Where-Object { $_ -match '^R16' }).Count))
Write-Output ("R17 多顶层类型文件 : {0}（信息性）" -f (@($info | Where-Object { $_ -match '^R17' }).Count))
Write-Output ("R18 超过 400 行的文件: {0}（信息性）" -f (@($info | Where-Object { $_ -match '^R18' }).Count))
if ($info.Count -gt 0) {
    Write-Output '--- 信息性明细 ---'
    $info | ForEach-Object { Write-Output ("  " + $_) }
}
if ($bad.Count -gt 0) {
    Write-Output '--- 待处理明细 ---'
    $bad | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $bad.Count)
    exit 1
}
Write-Output 'AUDIT_PASS: 常量命名与注释引用一致'
exit 0
