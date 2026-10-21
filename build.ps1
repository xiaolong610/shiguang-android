param([switch]$Test)
$ErrorActionPreference='Stop'
$sourceRoot=$PSScriptRoot
$buildRoot=$sourceRoot
# Windows Java test workers can fail to load classes from a non-ASCII path.
if($sourceRoot -match '[^\x00-\x7F]') {
    $buildRoot=Join-Path $env:LOCALAPPDATA ('ShiguangBuild\'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $buildRoot 'app') -Force | Out-Null
    foreach($name in @('settings.gradle.kts','build.gradle.kts','gradle.properties','local.properties','gradlew','gradlew.bat')) {
        $file=Join-Path $sourceRoot $name
        if(Test-Path -LiteralPath $file) {Copy-Item -LiteralPath $file -Destination $buildRoot -Force}
    }
    if(Test-Path -LiteralPath (Join-Path $sourceRoot 'gradle')) {Copy-Item -LiteralPath (Join-Path $sourceRoot 'gradle') -Destination $buildRoot -Recurse}
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'app\build.gradle.kts') -Destination (Join-Path $buildRoot 'app')
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'app\src') -Destination (Join-Path $buildRoot 'app') -Recurse
    if(Test-Path -LiteralPath (Join-Path $sourceRoot 'third_party')) {Copy-Item -LiteralPath (Join-Path $sourceRoot 'third_party') -Destination $buildRoot -Recurse}
}
Set-Location -LiteralPath $buildRoot
if(-not $env:JAVA_HOME -or -not(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw '请先配置 JAVA_HOME（建议 JDK 21）。' }
if(-not $env:ANDROID_HOME -or -not(Test-Path -LiteralPath $env:ANDROID_HOME)) {
    if(Test-Path -LiteralPath 'C:\Android\Sdk') {$env:ANDROID_HOME='C:\Android\Sdk'} else {throw '请先安装 Android SDK，并配置 ANDROID_HOME。'}
}
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$buildFlags=@('--console=plain','--max-workers=2')
if($env:HTTPS_PROXY) {
    $taskProxy=[uri]$env:HTTPS_PROXY
    if($taskProxy.Scheme -eq 'http' -and -not $taskProxy.UserInfo) {
        $buildFlags+=@("-Dhttp.proxyHost=$($taskProxy.Host)","-Dhttp.proxyPort=$($taskProxy.Port)","-Dhttps.proxyHost=$($taskProxy.Host)","-Dhttps.proxyPort=$($taskProxy.Port)")
    }
}
$runner=if(Test-Path -LiteralPath '.\gradlew.bat') {'.\gradlew.bat'} else {'gradle.bat'}
$tasks=@(':app:assembleDebug')
if($Test){$tasks+= ':app:testDebugUnitTest'}
& $runner @buildFlags @tasks
if($LASTEXITCODE -ne 0){throw '构建失败，请查看上方错误。'}
$versionMatch=[regex]::Match((Get-Content -LiteralPath (Join-Path $sourceRoot 'app\build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"')
if(-not $versionMatch.Success){throw '无法读取版本号。'}
$apkName="拾光-$($versionMatch.Groups[1].Value)-预览版.apk"
$releaseRoot=Join-Path $sourceRoot 'release'
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
Copy-Item -LiteralPath '.\app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $releaseRoot $apkName) -Force
if($Test) {Copy-Item -LiteralPath '.\app\build\reports\tests\testDebugUnitTest' -Destination (Join-Path $releaseRoot 'unit-test-report') -Recurse -Force}
Write-Host "已生成 $(Join-Path $releaseRoot $apkName)"
