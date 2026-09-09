# 拾题桌面壳一键打包（Windows）
# 步骤：mvn package（后端 fat jar）→ jlink 裁剪 JRE → 复制到 src-tauri 根 → tauri build（NSIS）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot   # 仓库根
$tauri = Join-Path $root 'tauri'
$srcTauri = Join-Path $tauri 'src-tauri'
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
  $jdk = 'C:\Program Files\Java\jdk-21'
}
$mvn = 'C:\Maven\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path $mvn)) { $mvn = 'mvn' }

Write-Host '== 1/4 后端打包 =='
Push-Location $root
& $mvn -nsu -q -DskipTests package
if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 }
Pop-Location
$jar = Join-Path $root 'target\Tiku-0.0.1-SNAPSHOT.jar'

Write-Host '== 2/4 jlink 裁剪 JRE（未生成时） =='
$jre = Join-Path $root 'target\jre'
if (-not (Test-Path (Join-Path $jre 'bin\java.exe'))) {
  $mods = 'java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.nio.mapmode,jdk.unsupported,jdk.localedata,jdk.zipfs'
  & (Join-Path $jdk 'bin\jlink.exe') --add-modules $mods --output $jre --strip-debug --no-header-files --no-man-pages --compress=zip-6
  if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Host '== 3/4 复制资源到 src-tauri 根（tauri.conf bundle.resources: jre/、app.jar） =='
if (Test-Path (Join-Path $srcTauri 'jre')) { Remove-Item -Recurse -Force (Join-Path $srcTauri 'jre') }
Copy-Item -Recurse $jre (Join-Path $srcTauri 'jre')
Copy-Item $jar (Join-Path $srcTauri 'app.jar') -Force
Write-Host ('    jre=' + [Math]::Round((Get-ChildItem (Join-Path $srcTauri 'jre') -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1) + 'MB  jar=' + [Math]::Round((Get-Item (Join-Path $srcTauri 'app.jar')).Length/1MB,1) + 'MB')

Write-Host '== 4/5 tauri build（NSIS 安装包） =='
Push-Location $srcTauri
node (Join-Path $root 'frontend\node_modules\@tauri-apps\cli\tauri.js') build --bundles nsis
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { exit 1 }
Write-Host '完成：安装包见 src-tauri/target/release/bundle/nsis/'

Write-Host '== 5/5 便携版 zip（解压即用） =='
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
