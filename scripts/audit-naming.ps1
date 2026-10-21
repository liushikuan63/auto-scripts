<#
.SYNOPSIS
  命名一致性审查：类名必须是 UpperCamelCase，函数名必须是 lowerCamelCase。

.DESCRIPTION
  与 audit-code-health.ps1 分开成独立脚本：命名规则只看标识符本身，
  不需要全仓语料，单独跑更短、更好定位问题。

  排除项：
    - @Composable / @Preview 标注的函数（Compose 约定允许 PascalCase）
    - 扩展函数取最后一段作为函数名
    - operator 与属性访问器（get / set 等本就是小写）
    - 仓库内自带的 OpenCV API 桩（org/opencv/** 是第三方 API 形状）
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'
$srcRoot = Join-Path $Root 'app/src/main/java'
if (-not (Test-Path $srcRoot)) { Write-Output 'AUDIT_SKIP: 未找到源码目录'; exit 2 }

$classRx = [regex]::new('^(?:internal |public |private |open |abstract |sealed |data |enum |value |annotation )*(?:class|interface|object|enum class)\s+([A-Za-z_]\w*)')
$funRx = [regex]::new('^\s*(?:internal |public |private |override |suspend |inline |operator |tailrec |external )*fun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)')
$extRx = [regex]::new('^\s*(?:internal |public |private |override |suspend |inline |operator |tailrec |external )*fun\s+(?:<[^>]*>\s*)?[A-Za-z_][\w.]*\.([A-Za-z_]\w*)\s*\(')

$files = @(Get-ChildItem $srcRoot -Recurse -Filter *.kt -File | Where-Object { $_.FullName -notmatch '[\\/]org[\\/]opencv[\\/]' })
$bad = New-Object System.Collections.Generic.List[string]

foreach ($f in $files) {
    $rel = $f.FullName.Substring($Root.Length).TrimStart([char]'\', [char]'/')
    $L = [System.IO.File]::ReadAllLines($f.FullName)
    for ($i = 0; $i -lt $L.Count; $i++) {
        $t = $L[$i].Trim()
        if ($t -eq '') { continue }

        $m = $classRx.Match($t)
        if ($m.Success) {
            $n = $m.Groups[1].Value
            if ($n -ne '' -and -not [char]::IsUpper($n[0])) {
                $bad.Add("R6 命名不一致 ${rel}:$($i + 1)  类名 '$n' 应为 UpperCamelCase")
            }
        }

        $m = $funRx.Match($t)
        if ($m.Success) {
            $n = $m.Groups[1].Value
            $me = $extRx.Match($t)
            if ($me.Success) { $n = $me.Groups[1].Value }
            if ($n -eq '' -or [char]::IsUpper($n[0])) {
                $ctx = ''
                for ($b = [Math]::Max(0, $i - 4); $b -lt $i; $b++) { $ctx += $L[$b] }
                if ($ctx -notmatch '@Composable|@Preview') {
                    $bad.Add("R6 命名不一致 ${rel}:$($i + 1)  函数名 '$n' 应为 lowerCamelCase")
                }
            }
        }
    }
}

Write-Output '=== 命名一致性审查 ==='
Write-Output ("R6 命名不一致: {0}" -f $bad.Count)
if ($bad.Count -gt 0) {
    Write-Output '--- 明细 ---'
    $bad | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $bad.Count)
    exit 1
}
Write-Output 'AUDIT_PASS: 命名规范一致'
exit 0
