<#
.SYNOPSIS
  审查脚本自检：用一份「故意违规」的样本验证 audit-code-health.ps1 真的会红，且八条规则都真的能命中。

.DESCRIPTION
  探针自检：若审查脚本失效（永远返回 0，或某条规则形同虚设），本脚本会以非 0 退出，
  使 CI 在「门禁形同虚设」时立刻失败，而不是让违规代码静默流入。

  样本逐条覆盖 R1–R8：孤儿 KDoc、失效分段标记、零引用死代码、缺类级 KDoc、
  公开成员无注释、命名不一致、注释引用失效、缺文件级说明。
#>
[CmdletBinding()]
param(
    [string]$AuditScript = (Join-Path $PSScriptRoot 'audit-code-health.ps1')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $AuditScript)) { Write-Output "找不到审查脚本：$AuditScript"; exit 2 }

$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("farmbot-audit-fixture-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$srcDir = Join-Path $fixture 'app/src/main/java/com/farmbot/fixture'
New-Item -ItemType Directory -Force -Path $srcDir | Out-Null

$sample = @'
package com.farmbot.fixture

/**
 * 这段注释后面没有声明 —— R1 应命中
 */

/** 这个类没有类级说明 —— R4 应命中 */
class Violations {

    fun used() = helper()

    private fun helper(): Int = 1

    private fun neverCalled(): Int = 2

    /** 提到了并不存在的 `ghostIdentifier` —— R7 应命中 */
    fun referencesGhost() = 0

    public val undocumentedMember: Int = 0

    fun BadNamedFunction() = 0

    // ============ 这一段是空的 ============
}

class AlsoMissingDoc {
    fun ping() = 1
}

class badClassName {
    fun ping() = 1
}
'@
[System.IO.File]::WriteAllText((Join-Path $srcDir 'Violations.kt'), ($sample -replace "`r`n", "`n"))

Write-Output '=== 审查脚本自检（对故意违规的样本） ==='
$out = & pwsh -NoProfile -File $AuditScript -Root $fixture 2>&1
$code = $LASTEXITCODE
$out | ForEach-Object { Write-Output $_ }
$text = ($out | Out-String)

# 命名规则在独立脚本里，单独跑并合并结果
$namingScript = Join-Path $PSScriptRoot 'audit-naming.ps1'
$nOut = & pwsh -NoProfile -File $namingScript -Root $fixture 2>&1
$nCode = $LASTEXITCODE
$nOut | ForEach-Object { Write-Output $_ }
$text += ($nOut | Out-String)
if ($nCode -ne 1) {
    Write-Output "SELFTEST_FAIL: 命名脚本对违规样本的退出码应为 1，实际为 $nCode"
    exit 1
}

Remove-Item -Recurse -Force $fixture -ErrorAction SilentlyContinue

if ($code -ne 1) {
    Write-Output "SELFTEST_FAIL: 违规样本的退出码应为 1，实际为 $code（审查脚本可能已失效）"
    exit 1
}

# 每条规则都必须在样本上真的命中，否则该规则形同虚设
$missing = @()
foreach ($r in 1..8) {
    if ($text -notmatch ("(?m)^R{0} .*?:\s*([1-9]\d*)" -f $r)) { $missing += $r }
}
if ($missing.Count -gt 0) {
    Write-Output ("SELFTEST_FAIL: 以下规则在违规样本上没有命中：R" + ($missing -join ', R'))
    exit 1
}

Write-Output 'SELFTEST_PASS: 违规样本被正确判红（退出码 1），且 R1–R8 每条都真的命中'
exit 0
