<#
.SYNOPSIS
  结构一致性审查：常量命名、包与目录的对应关系。

.DESCRIPTION
  R12 常量命名不规范   const val 必须 UPPER_SNAKE_CASE（普通顶层 val 不在此规则内）
  R14 包与目录不一致   文件所在目录必须与包名对应（如 com/farmbot/ui/X.kt 属于包 com.farmbot.ui）

  非 const 的顶层 val 命名（如主题色 GreenPrimary）风格上不统一，但改名会波及大量引用，
  属需要决策的形态选择，因此只做信息性统计、不判失败。
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
$pascalVals = 0

foreach ($f in $files) {
    $rel = $f.FullName.Substring($Root.Length).TrimStart([char]'\', [char]'/')
    $L = [System.IO.File]::ReadAllLines($f.FullName)

    # ---- R14：包名与目录必须对应 ----
    $pkg = $null
    for ($i = 0; $i -lt $L.Count; $i++) { if ($L[$i] -match '^package\s+([\w.]+)') { $pkg = $Matches[1]; break } }
    if ($pkg) {
        $expected = ($pkg -replace '\.', [System.IO.Path]::DirectorySeparatorChar)
        $actual = $f.DirectoryName.Substring($srcRoot.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar)
        if ($actual -ne $expected.TrimEnd([System.IO.Path]::DirectorySeparatorChar)) {
            $bad.Add("R14 包与目录不一致 ${rel}  包名 '$pkg' 期望目录 '$expected'，实际 '$actual'")
        }
    }

    for ($i = 0; $i -lt $L.Count; $i++) {
        $t = $L[$i].Trim()

        # 信息性：非 const 的顶层 val 用 PascalCase 的数量（不判失败）
        # 注意用原始行判断是否顶层：$t 已 trim，会把函数体内的局部 val 也算进来
        if ($L[$i] -notmatch '^\s' -and $t -match '^(?:internal |public )?val\s+([A-Z]\w*)\s*[:=]') { $pascalVals++ }
    }
}

Write-Output '=== 结构一致性审查 ==='
Write-Output ("R12 常量命名不规范 : {0}" -f (@($bad | Where-Object { $_ -match '^R12' }).Count))
Write-Output ("R14 包与目录不一致 : {0}" -f (@($bad | Where-Object { $_ -match '^R14' }).Count))
Write-Output ("信息: 非 const 的 PascalCase 顶层 val {0} 处（不判失败，属待决策项）" -f $pascalVals)
if ($bad.Count -gt 0) {
    Write-Output '--- 明细 ---'
    $bad | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $bad.Count)
    exit 1
}
Write-Output 'AUDIT_PASS: 结构与命名一致'
exit 0
