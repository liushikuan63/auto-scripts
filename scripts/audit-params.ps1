<#
.SYNOPSIS
  KDoc 参数一致性审查：@param / @return 必须与函数签名对得上。

.DESCRIPTION
  R13 注释参数不存在    KDoc 里的 @param xxx 必须是函数签名里的参数名
  R13 注释与返回类型矛盾  KDoc 写了 @return，但函数显式声明返回 Unit

  单独成脚本：参数提取要跨行处理签名、带默认值的参数里有括号，
  与其它规则共用循环状态容易互相干扰（见 R6 那次教训）。

  参数名提取方式：取签名第一个 ( 与最后一个 ) 之间的文本，
  再按「名字 + 冒号 + 类型」抽取参数名 —— 这样能正确处理
  多行签名与含括号的方法调用默认值。
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

foreach ($f in $files) {
    $rel = $f.FullName.Substring($Root.Length).TrimStart([char]'\', [char]'/')
    $L = [System.IO.File]::ReadAllLines($f.FullName)

    for ($i = 0; $i -lt $L.Count; $i++) {
        if (-not $L[$i].Trim().StartsWith('/**')) { continue }
        $s = $i
        while ($i -lt $L.Count -and -not $L[$i].Trim().EndsWith('*/')) { $i++ }
        $doc = ($L[$s..$i] -join ' ')
        $j = $i + 1
        while ($j -lt $L.Count -and $L[$j].Trim() -eq '') { $j++ }
        if ($j -ge $L.Count) { continue }
        $sig = $L[$j].Trim()
        if ($sig -notmatch '^fun\s') { continue }

        # 签名可能跨行：一直拼到参数列表收尾（形如 ") : Type {" 或 ") {")
        $k = $j
        $sigFull = $sig
        while ($k + 1 -lt $L.Count -and ($k - $j) -lt 10 -and $sigFull -notmatch '\)\s*(:\s*[\w<>,?. ]+)?\s*\{?\s*$') {
            $k++
            $sigFull = $sigFull + ' ' + $L[$k].Trim()
        }

        # 参数名：整段签名里按「名字 + 冒号」抽取（能正确处理多行签名与带括号的方法调用默认值）
        $params = @()
        $params = @([regex]::Matches($sigFull, '([A-Za-z_][A-Za-z0-9_]*)\s*:') | ForEach-Object { $_.Groups[1].Value })

        foreach ($m in [regex]::Matches($doc, '@param\s+([A-Za-z_][A-Za-z0-9_]*)')) {
            $pn = $m.Groups[1].Value
            if ($params -notcontains $pn) {
                $bad.Add("R13 注释参数不存在 ${rel}:$($s + 1)  @param '$pn' 不在函数签名里（实际参数：$($params -join ', ')）")
            }
        }

        if ($doc -match '@return') {
            $rt = [regex]::Match($sigFull, '\)\s*:\s*([A-Za-z_][A-Za-z0-9_<>?.,]*)')
            if ($rt.Success -and $rt.Groups[1].Value -eq 'Unit') {
                $bad.Add("R13 注释与返回类型矛盾 ${rel}:$($s + 1)  注释写了 @return，但函数返回 Unit")
            }
        }
    }
}

Write-Output '=== KDoc 参数一致性审查 ==='
Write-Output ("R13 注释参数矛盾 : {0}" -f $bad.Count)
if ($bad.Count -gt 0) {
    Write-Output '--- 明细 ---'
    $bad | ForEach-Object { Write-Output ("  " + $_) }
    Write-Output ''
    Write-Output ("AUDIT_FAIL: {0} 项待处理" -f $bad.Count)
    exit 1
}
Write-Output 'AUDIT_PASS: KDoc 参数与签名一致'
exit 0
