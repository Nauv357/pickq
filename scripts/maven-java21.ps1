<#
.SYNOPSIS
    使用已安装的 JDK 21 执行 Maven Wrapper。

.DESCRIPTION
    新机器常见的 JAVA_HOME 仍指向 JDK 17，而本项目需要 Java 21。本脚本会优先使用
    JAVA_HOME 中的 21，再自动查找 Windows 默认 JDK 目录；找不到时给出明确安装提示。

.EXAMPLE
    .\scripts\maven-java21.ps1 test

.EXAMPLE
    .\scripts\maven-java21.ps1 -MavenArguments @('-DskipTests', 'package')
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArguments = @('test')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 原生命令（java / mvnw.cmd）会把正常输出写进 stderr，在 $ErrorActionPreference='Stop' 下
# Windows PowerShell 5.1 会把它当成错误中断脚本（`java -version` 就会触发）。
# 用这个包装调用原生命令：临时切到 Continue，只按退出码判断成败。
function Invoke-Native {
    param([scriptblock]$Body)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Body 2>&1 | ForEach-Object { Write-Host $_ } } finally { $ErrorActionPreference = $previous }
    return $LASTEXITCODE
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory = $true)][string]$JavaHome)

    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        return $null
    }
    $versionOutput = ''
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $versionOutput = (& $javaExecutable -version 2>&1 | Out-String) } finally { $ErrorActionPreference = $previous }
    $match = [regex]::Match($versionOutput, '(?:version\s+")?(\d+)(?:\.|"|\s)')
    if (-not $match.Success) {
        return $null
    }
    return [int]$match.Groups[1].Value
}

$candidateHomes = [System.Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidateHomes.Add($env:JAVA_HOME)
}

$javaRoot = Join-Path $env:ProgramFiles 'Java'
if (Test-Path -LiteralPath $javaRoot -PathType Container) {
    Get-ChildItem -LiteralPath $javaRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending |
        ForEach-Object { $candidateHomes.Add($_.FullName) }
}

$java21Home = $null
foreach ($candidateHome in $candidateHomes | Select-Object -Unique) {
    if ((Get-JavaMajorVersion -JavaHome $candidateHome) -eq 21) {
        $java21Home = $candidateHome
        break
    }
}

if ($null -eq $java21Home) {
    throw '未找到 JDK 21。请安装 JDK 21，或将 JAVA_HOME 设置为 JDK 21 根目录后重试。'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    throw "未找到 Maven Wrapper：$mavenWrapper"
}

$env:JAVA_HOME = $java21Home
$env:Path = "$(Join-Path $java21Home 'bin');$env:Path"
Write-Host "使用 JDK 21：$java21Home"
$exitCode = Invoke-Native { & $mavenWrapper @MavenArguments }
exit $exitCode
