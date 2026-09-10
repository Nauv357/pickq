# 拾题桌面壳一键打包（Windows）
# 步骤：前端构建（frontend/dist → jar 内 classpath:/static）→ mvn package（后端 fat jar）
#       → jlink 裁剪 JRE → 复制到 src-tauri 根 → tauri build（NSIS）→ 便携版 zip
#
# ⚠️ 前端必须在这里构建：缺 frontend/dist 时 Maven 只告警不报错，
#    会静默产出"界面停留在旧版"的安装包（0.1.16 曾因此返工）。
param(
  [switch]$SkipFrontend,        # 已经手动构建过前端时可跳过
  [switch]$AllowStaleFrontend   # 允许 dist 比 src 旧（仅调试用，正式发版不要用）
)
$ErrorActionPreference = 'Stop'

# 原生命令（npm / mvn / jlink / node）会把正常日志写进 stderr，在 $ErrorActionPreference='Stop'
# 下 PowerShell 会把它当成错误直接中断脚本（历史坑：tauri CLI、npm 都因此被误判为失败）。
# 统一用这个包装调用：临时切到 Continue、输出原样打到控制台，只按退出码判断成败。
function Invoke-Native {
  param([scriptblock]$Body)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Body 2>&1 | ForEach-Object { Write-Host $_ } } finally { $ErrorActionPreference = $prev }
  return $LASTEXITCODE
}
$root = Split-Path -Parent $PSScriptRoot   # 仓库根
$tauri = Join-Path $root 'tauri'
$srcTauri = Join-Path $tauri 'src-tauri'
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
  $jdk = 'C:\Program Files\Java\jdk-21'
}
$mvn = 'C:\Maven\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path $mvn)) { $mvn = 'mvn' }

Write-Host '== 1/6 前端构建（产物进 jar 的 classpath:/static） =='
$fe = Join-Path $root 'frontend'
$dist = Join-Path $fe 'dist'
if ($SkipFrontend) {
  Write-Host '    已跳过（-SkipFrontend）'
} else {
  Push-Location $fe
  $feCode = Invoke-Native { npm run build }
  Pop-Location
  if ($feCode -ne 0) { Write-Host '前端构建失败，已中止打包' -ForegroundColor Red; exit 1 }
}
if (-not (Test-Path (Join-Path $dist 'index.html'))) {
  Write-Host '缺少 frontend\dist\index.html：请先执行 cd frontend; npm install; npm run build' -ForegroundColor Red
  exit 1
}
if (-not $AllowStaleFrontend) {
  $distTime = (Get-Item (Join-Path $dist 'index.html')).LastWriteTime
  $srcTime = (Get-ChildItem (Join-Path $fe 'src') -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
  if ($srcTime -gt $distTime) {
    Write-Host ('前端产物落后于源码（dist ' + $distTime + ' < src ' + $srcTime + '）：请重新构建，或加 -AllowStaleFrontend 跳过检查') -ForegroundColor Red
    exit 1
  }
  Write-Host ('    dist=' + [Math]::Round((Get-ChildItem $dist -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1) + 'MB  构建于 ' + $distTime)
}

Write-Host '== 2/6 后端打包 =='
Push-Location $root
$mvnCode = Invoke-Native { & $mvn -nsu -q -DskipTests package }
Pop-Location
if ($mvnCode -ne 0) { exit 1 }
$jar = Join-Path $root 'target\Tiku-0.0.1-SNAPSHOT.jar'

Write-Host '== 3/6 jlink 裁剪 JRE（未生成时） =='
$jre = Join-Path $root 'target\jre'
if (-not (Test-Path (Join-Path $jre 'bin\java.exe'))) {
  $mods = 'java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.nio.mapmode,jdk.unsupported,jdk.localedata,jdk.zipfs'
  $jlinkCode = Invoke-Native { & (Join-Path $jdk 'bin\jlink.exe') --add-modules $mods --output $jre --strip-debug --no-header-files --no-man-pages --compress=zip-6 }
  if ($jlinkCode -ne 0) { exit 1 }
}

Write-Host '== 4/6 复制资源到 src-tauri 根（tauri.conf bundle.resources: jre/、app.jar） =='
if (Test-Path (Join-Path $srcTauri 'jre')) { Remove-Item -Recurse -Force (Join-Path $srcTauri 'jre') }
Copy-Item -Recurse $jre (Join-Path $srcTauri 'jre')
Copy-Item $jar (Join-Path $srcTauri 'app.jar') -Force
Write-Host ('    jre=' + [Math]::Round((Get-ChildItem (Join-Path $srcTauri 'jre') -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1) + 'MB  jar=' + [Math]::Round((Get-Item (Join-Path $srcTauri 'app.jar')).Length/1MB,1) + 'MB')

Write-Host '== 5/6 tauri build（NSIS 安装包） =='
Push-Location $srcTauri
$code = Invoke-Native { node (Join-Path $root 'frontend\node_modules\@tauri-apps\cli\tauri.js') build --bundles nsis }
Pop-Location
if ($code -ne 0) { exit 1 }
Write-Host '完成：安装包见 src-tauri/target/release/bundle/nsis/'

Write-Host '== 6/6 便携版 zip（解压即用） =='
$exe = Join-Path $srcTauri 'target\release\tiku-desktop.exe'
$portableDir = Join-Path $srcTauri 'target\release\portable\拾题-便携版'
if (Test-Path $portableDir) { Remove-Item -Recurse -Force $portableDir }
New-Item -ItemType Directory -Path $portableDir | Out-Null
Copy-Item $exe (Join-Path $portableDir 'tiku-desktop.exe')
Copy-Item -Recurse $jre (Join-Path $portableDir 'jre')
Copy-Item (Join-Path $srcTauri 'app.jar') (Join-Path $portableDir 'app.jar')
Copy-Item (Join-Path $tauri 'portable-assets\*') $portableDir
$zipOut = Join-Path $srcTauri 'target\release\bundle\zip\拾题-便携版.zip'
New-Item -ItemType Directory -Force -Path (Split-Path $zipOut) | Out-Null
if (Test-Path $zipOut) { Remove-Item $zipOut }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($portableDir, $zipOut, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Write-Host ('完成：便携版见 ' + $zipOut + '（' + [Math]::Round((Get-Item $zipOut).Length/1MB,1) + 'MB）')
