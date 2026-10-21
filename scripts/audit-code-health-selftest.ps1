<#
.SYNOPSIS
  审查脚本自检：用一份「故意违规」的样本验证 audit-code-health.ps1 真的会红。

.DESCRIPTION
  探针自检：若审查脚本失效（永远返回 0），本脚本会以非 0 退出，
  使 CI 在「门禁形同虚设」时立刻失败，而不是让违规代码静默流入。

  样本覆盖四条规则：R1 孤儿 KDoc、R2 失效分段标记、R3 零引用死代码、R4 缺类级 KDoc。
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

    // ============ 这一段是空的 ============
}

class AlsoMissingDoc {
    fun ping() = 1
}
'@
[System.IO.File]::WriteAllText((Join-Path $srcDir 'Violations.kt'), ($sample -replace "`r`n", "`n"))

Write-Output '=== 审查脚本自检（对故意违规的样本） ==='
& pwsh -NoProfile -File $AuditScript -Root $fixture
$code = $LASTEXITCODE

Remove-Item -Recurse -Force $fixture -ErrorAction SilentlyContinue

if ($code -ne 1) {
    Write-Output "SELFTEST_FAIL: 违规样本的退出码应为 1，实际为 $code（审查脚本可能已失效）"
    exit 1
}
Write-Output 'SELFTEST_PASS: 违规样本被正确判红（退出码 1）'
exit 0
