param(
 [string]$CosyVoiceRoot='D:\AIsoft\CosyVoice',
 [switch]$Lan,
 [string]$Address='',
 [int]$Port=8765,
 [switch]$PrepareOnly
)
$ErrorActionPreference='Stop'
if($Port -lt 1024 -or $Port -gt 65535){throw '端口需要在1024—65535之间'}
$python=Join-Path $CosyVoiceRoot '.venv\Scripts\python.exe'
$reference=Join-Path $PSScriptRoot 'synthetic-reference.wav'
if(!(Test-Path -LiteralPath $python)){throw '找不到CosyVoice运行环境，请通过 -CosyVoiceRoot 指定安装目录'}
if(!(Test-Path -LiteralPath $reference)){throw '缺少内置合成音色，请使用完整的电脑语音助手包'}
if(!$PrepareOnly -and @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $Port }).Count -gt 0){throw '端口已被使用，请关闭已有服务或指定其他端口'}
if($Lan){
 if(!$Address){$Address=Get-NetIPConfiguration|Where-Object{$_.IPv4DefaultGateway -and $_.IPv4Address}|ForEach-Object{$_.IPv4Address.IPAddress}|Where-Object{$_ -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'}|Select-Object -First 1}
 $parts=$Address -split '\.'
 $literal=$parts.Count -eq 4 -and @($parts|Where-Object{$_ -notmatch '^\d{1,3}$' -or [int]$_ -gt 255}).Count -eq 0
 if(!$Address -or !$literal -or !([int]$parts[0] -eq 10 -or ([int]$parts[0] -eq 192 -and [int]$parts[1] -eq 168) -or ([int]$parts[0] -eq 172 -and [int]$parts[1] -ge 16 -and [int]$parts[1] -le 31))){throw '无法确定局域网IPv4地址，请用 -Address 指定电脑在手机同一网络上的IP'}
 $listen='0.0.0.0'
}else{$Address='127.0.0.1';$listen='127.0.0.1'}
$runtime=Join-Path $PSScriptRoot '.runtime'
[IO.Directory]::CreateDirectory($runtime)|Out-Null
$keyFile=Join-Path $runtime 'voice-server.key'
if(!(Test-Path -LiteralPath $keyFile)){
 $bytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create();try{$random.GetBytes($bytes)}finally{$random.Dispose()}
 [IO.File]::WriteAllText($keyFile,[Convert]::ToBase64String($bytes),[Text.UTF8Encoding]::new($false))
}
$pairing=Join-Path $runtime '拾光AI语音配对.json'
$value=[ordered]@{format='shiguang-ai-pairing';version=1;url="http://${Address}:$Port";token=(Get-Content -LiteralPath $keyFile -Raw).Trim();style='natural'}
[IO.File]::WriteAllText($pairing,(ConvertTo-Json -InputObject $value),[Text.UTF8Encoding]::new($false))
Write-Host ('配对文件已保存到：'+$pairing)
Write-Host ('语音服务地址：'+$value.url)
if($PrepareOnly){Write-Host '仅生成配对文件，未启动服务';return}
Write-Host '把配对JSON传到手机，在拾光听书的AI配对窗口导入。等待模型加载完成后再连接。'
if($Lan){Write-Host '手机和电脑需在同一局域网；若系统防火墙询问，请按你所用的网络选择。此脚本不会修改防火墙。'}
Write-Host '此窗口关闭或按Ctrl+C后，AI合成停止，手机系统听书不受影响。'
$env:PYTHONUTF8='1'
& $python -u (Join-Path $PSScriptRoot 'server.py') --cosyvoice $CosyVoiceRoot --reference $reference --key-file $keyFile --host $listen --port $Port
if($LASTEXITCODE -ne 0){throw '语音服务已退出，请查看上方错误'}
