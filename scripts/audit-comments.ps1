<#
.SYNOPSIS
  注释与代码的一致性审查：注释里说的与代码做的必须对得上。

.DESCRIPTION
  R9  文件名与顶层类型不一致   .kt 文件名应与文件内某个顶层类型同名（Kotlin 惯例）
  R10 注释与返回类型矛盾       注释写「返回 true / 返回 false」但函数并不是 Boolean 返回
  R11 行内注释引用失效         // 注释里反引号标注的标识符在代码中已不存在

  单独成脚本：这三条只依赖单文件与项目语料，不与全仓统计共用循环状态。
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

# 语料：去掉注释后的全部代码（用于判断标识符是否还存在）
$codeOnly = New-Object System.Text.StringBuilder
foreach ($f in $files) {
    $t = [System.IO.File]::ReadAllText($f.FullName)
    $t = [regex]::Replace($t, '(?s)/\*.*?\*/', '')
    $t = [regex]::Replace($t, '(?<!:)//.*$', '', 'Multiline')
    [void]$codeOnly.Append($t)
}
$codeText = $codeOnly.ToString()

foreach ($f in $files) {
    $rel = $f.FullName.Substring($Root.Length).TrimStart([char]'\', [char]'/')
    $L = [System.IO.File]::ReadAllLines($f.FullName)

    # ---- R9：文件名应与文件内某个顶层类型同名 ----
    $topTypes = @()
    foreach ($line in $L) {
        if ($line -match '^(?:internal |public |private |open |abstract |sealed |data |enum |value |annotation )*(?:class|interface|object|enum class)\s+([A-Za-z_]\w*)') {
            $topTypes += $Matches[1]
        }
    }
    if ($topTypes.Count -gt 0 -and ($topTypes -notcontains $f.BaseName)) {
        $bad.Add("R9 文件名不一致 ${rel}  文件名 '$($f.BaseName)' 与文件内顶层类型（$($topTypes -join ', ')）都不匹配")
    }

    for ($i = 0; $i -lt $L.Count; $i++) {
        $t = $L[$i].Trim()

        # ---- R10：注释说返回 true/false，函数却不是 Boolean 返回 ----
        if ($t.StartsWith('/**')) {
            $s = $i
            while ($i -lt $L.Count -and -not $L[$i].Trim().EndsWith('*/')) { $i++ }
            $doc = ($L[$s..$i] -join ' ')
            $j = $i + 1
            while ($j -lt $L.Count -and $L[$j].Trim() -eq '') { $j++ }
            $decl = if ($j -lt $L.Count) { $L[$j].Trim() } else { '' }
            if ($decl -match '^fun\s' -and $doc -match '返回\s*(true|false)') {
                $m = [regex]::Match($decl, 'fun\s+(?:<[^>]*>\s*)?[A-Za-z_][\w.]*\s*\([^)]*\)\s*:\s*([^\s{]+)')
                $ret = if ($m.Success) { $m.Groups[1].Value } else { 'Unit' }
                if ($ret -notmatch '^Boolean\??$') {
                    $bad.Add("R10 注释与返回类型矛盾 ${rel}:$($s + 1)  注释写「返回 true/false」，但函数返回类型是 $ret")
                }
            }
            continue
        }

        # ---- R11：行内注释里的反引号标识符已不存在 ----
        if ($t.StartsWith('//')) {
            foreach ($m in [regex]::Matches($t, '`([A-Za-z_][A-Za-z0-9_]*)`')) {
                $id = $m.Groups[1].Value
                if ($codeText -notmatch ('(?<![\w])' + [regex]::Escape($id) + '(?![\w])')) {
                    $bad.Add("R11 行内注释引用失效 ${rel}:$($i + 1)  注释里的 `$id 在代码中已不存在")
                }
            }
        }
    }
}

Write-Output '=== 注释与代码一致性审查 ==='
Write-Output ("R9  文件名不一致   : {0}" -f (@($bad | Where-Object { $_ -match '^R9' }).Count))
Write-Output ("R10 注释与返回矛盾 : {0}" -f (@($bad | Where-Object { $_ -match '^R10' }).Count))
Write-Output ("R11 行内注释失效  : {0}" -f (@($bad | Where-Object { $_ -match '^R11' }).Count))
if ($bad.Count -gt 0) {
    Write-Output '--- 明细 ---'
    $bad | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $bad.Count)
    exit 1
}
Write-Output 'AUDIT_PASS: 注释与代码一致、文件名与类型一致'
exit 0
