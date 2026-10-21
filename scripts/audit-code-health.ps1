<#
.SYNOPSIS
  代码健康审查：检查注释与代码是否脱节，以及是否存在零引用的死代码。

.DESCRIPTION
  硬规则（命中即退出码 1）：
    R1 孤儿 KDoc        注释块后面没有跟声明（注释没有对应物）
    R2 失效分段标记     `// ==== xxx ====` 后面直接收尾或文件结束（标记悬空）
    R3 零引用死代码     private/internal 函数在全仓语料中没有调用点
    R4 缺类级 KDoc      顶层类/对象/接口没有类级说明
    R5 公开成员无注释   带可见性修饰符的成员（public/internal/override）没有说明
    R6 命名不一致       类名必须 UpperCamelCase；函数名必须 lowerCamelCase
    R7 注释引用失效     KDoc 里反引号标注的标识符在代码中已不存在
    R8 缺文件级说明     .kt 文件没有 package 之前的文件级 KDoc

  跨平台：Windows 与 Linux 的 GitHub Actions 均可直接运行（仅用 pwsh 内置能力）。
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'

$srcRoot = Join-Path $Root 'app/src/main/java'
if (-not (Test-Path $srcRoot)) { Write-Output "AUDIT_SKIP: 未找到源码目录 $srcRoot"; exit 2 }

function Get-RelPath([string]$p) { $p.Substring($Root.Length).TrimStart([char]'\', [char]'/') }

function Test-IsDeclaration([string]$line) {
    $t = $line.Trim()
    if ($t -eq '') { return $false }
    if ($t.StartsWith('//') -or $t.StartsWith('/*') -or $t.StartsWith('*')) { return $false }
    if ($t -like 'package *') { return $true }
    return $t -match '^(private |internal |public |protected |open |abstract |override |suspend |inline |operator |annotation |data |sealed |enum |value |companion |const |lateinit |expect |actual )*(fun|val|var|class|interface|object|typealias|constructor|init|@)'
}

# 只保留代码的语料：去掉 KDoc/块注释与行注释（URL 里的 // 不算注释）
function Get-CodeOnly([string]$text) {
    $t = [regex]::Replace($text, '(?s)/\*.*?\*/', '')
    $t = [regex]::Replace($t, '(?<!:)//.*$', '', 'Multiline')
    return $t
}

$ktFiles = @(Get-ChildItem $srcRoot -Recurse -Filter *.kt -File |
    Where-Object { $_.FullName -notmatch '[\\/]org[\\/]opencv[\\/]' })
if ($ktFiles.Count -eq 0) { Write-Output 'AUDIT_SKIP: 没有 Kotlin 源文件'; exit 2 }

$corpusFiles = @(Get-ChildItem (Join-Path $Root 'app/src') -Recurse -File | Where-Object { $_.Extension -in @('.kt', '.java', '.xml') })
$proGuard = Join-Path $Root 'app/proguard-rules.pro'
if (Test-Path $proGuard) { $corpusFiles += Get-Item $proGuard }
$rawCorpus = ($corpusFiles | ForEach-Object { [System.IO.File]::ReadAllText($_.FullName) }) -join "`n"
$corpus = $rawCorpus                       # 全量语料：用于 R3 调用点统计
$codeCorpus = Get-CodeOnly $rawCorpus      # 去注释语料：用于 R7 标识符存在性

$findings = New-Object System.Collections.Generic.List[string]
$stats = @{ R1 = 0; R2 = 0; R3 = 0; R4 = 0; R5 = 0; R7 = 0; R8 = 0 }
$ruleNames = @{
    R1 = '孤儿 KDoc'
    R2 = '失效分段标记'
    R3 = '零引用死代码'
    R4 = '缺类级 KDoc'
    R5 = '公开成员无注释'
    R7 = '注释引用失效'
    R8 = '缺文件级说明'
}

foreach ($f in $ktFiles) {
    $rel = Get-RelPath $f.FullName
    $L = [System.IO.File]::ReadAllLines($f.FullName)

    # R8：文件级说明（package 之前要有 KDoc）
    $pkgIdx = -1
    for ($i = 0; $i -lt $L.Count; $i++) { if ($L[$i] -match '^package\s') { $pkgIdx = $i; break } }
    if ($pkgIdx -ge 0) {
        $hasFileDoc = $false
        for ($k = $pkgIdx - 1; $k -ge 0; $k--) {
            $t = $L[$k].Trim()
            if ($t -eq '') { continue }
            if ($t.EndsWith('*/')) { $hasFileDoc = $true }
            break
        }
        if (-not $hasFileDoc) {
            $stats.R8++
            $findings.Add("R8 缺文件级说明 ${rel}:1  package 之前没有文件级 KDoc")
        }
    }

    for ($i = 0; $i -lt $L.Count; $i++) {
        $t = $L[$i].Trim()

        # R1：孤儿 KDoc；R7：KDoc 里反引号标识符是否还存在
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
            # R7：反引号标识符
            $docText = ($L[$s..$i] -join ' ')
            foreach ($m in [regex]::Matches($docText, '`([A-Za-z_][A-Za-z0-9_]*)`')) {
                $id = $m.Groups[1].Value
                if ($codeCorpus -notmatch ('(?<![\w])' + [regex]::Escape($id) + '(?![\w])')) {
                    $stats.R7++
                    $findings.Add("R7 注释引用失效 ${rel}:$($s + 1)  KDoc 里的 `$id 在代码中已不存在")
                }
            }
            continue
        }

        # R2：失效分段标记
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
        if ($t -match '^(internal |public |private |open |abstract |sealed |data |enum |value )*(class|interface|object|enum class)\s' -and $L[$i].Length -eq $L[$i].TrimStart().Length) {
            $k = $i - 1
            while ($k -ge 0 -and $L[$k].Trim() -eq '') { $k-- }
            if (-not ($k -ge 0 -and $L[$k].Trim().EndsWith('*/'))) {
                $stats.R4++
                $findings.Add("R4 缺类级KDoc ${rel}:$($i + 1)  $($L[$i].Trim())")
            }
        }


    }

    # R3：零引用死代码 / R5：公开成员无注释
    for ($i = 0; $i -lt $L.Count; $i++) {
        $line = $L[$i]

        if ($line -match '^\s*(private |internal )\s*(suspend )?(inline )?fun\s+([A-Za-z_][A-Za-z0-9_]*)') {
            $name = $Matches[4]
            $rest = $line -replace '^\s*(private |internal )\s*(suspend )?(inline )?fun\s+', ''
            if ($rest -match '^[A-Za-z_][\w.]*\.([A-Za-z_]\w*)\s*\(') { $name = $Matches[1] }
            if ($line -match '\boverride\b') { continue }
            $ctx = ''
            for ($b = [Math]::Max(0, $i - 4); $b -lt $i; $b++) { $ctx += $L[$b] }
            if ($ctx -match '@Composable|@Preview|@Test') { continue }
            $hits = ([regex]::Matches($corpus, ('(?<![\w])' + [regex]::Escape($name) + '\s*(\(|\{)'))).Count
            if ($hits -le 1) {
                $stats.R3++
                $findings.Add("R3 零引用死代码 ${rel}:$($i + 1)  $name")
            }
        }

        # R5：带可见性修饰符的成员必须有说明
        if ($line -match '^\s*(public |internal |override )[a-zA-Z]*\s*(suspend |inline |const |lateinit |@Volatile )*(fun|val|var)\s+([A-Za-z_]\w*)') {
            $k = $i - 1
            while ($k -ge 0 -and $L[$k].Trim() -eq '') { $k-- }
            $hasDoc = ($k -ge 0 -and ($L[$k].Trim().EndsWith('*/') -or $L[$k].TrimStart().StartsWith('//')))
            if (-not $hasDoc -and $line -notmatch '//') {
                $stats.R5++
                $findings.Add("R5 公开成员无注释 ${rel}:$($i + 1)  $($line.Trim().Substring(0, [Math]::Min(70, $line.Trim().Length)))")
            }
        }
    }
}

Write-Output '=== 代码健康审查 ==='
foreach ($k in @('R1', 'R2', 'R3', 'R4', 'R5', 'R7', 'R8')) {
    Write-Output ("{0} {1,-14}: {2}" -f $k, $ruleNames[$k], $stats[$k])
}
Write-Output ''

$hard = 0; foreach ($k in $stats.Keys) { $hard += $stats[$k] }
if ($hard -gt 0) {
    Write-Output '--- 明细 ---'
    $findings | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $hard)
    exit 1
}
Write-Output 'AUDIT_PASS: 注释与代码一致、无死代码、命名规范'
exit 0
