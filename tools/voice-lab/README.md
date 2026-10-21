# 拾光电脑语音助手 0.5.0

配合拾光安卓版“AI情感听书”使用。0.5.0已接通手机配置、配对文件导入、合成队列和实际播放。默认听书仍是手机系统语音，无需电脑；AI模型在自己的电脑运行，手机接收生成音频。

正常使用依赖已安装的CosyVoice环境及Fun-CosyVoice3-0.5B权重，生成参考音色的研究探针另需CosyVoice-300M-SFT。模型权重、环境和其许可证不包含在助手ZIP中。随包提供新生成的内置合成参考音色，无需真人录音。

## 手机配对

1. 解压完整助手包，双击Start-LAN-Voice.cmd，等待模型加载完成。本机已有D:\AIsoft\CosyVoice；其他电脑需先安装环境与模型。
2. 手机与电脑使用同一局域网，把助手生成的.runtime/拾光AI语音配对.json传到手机。
3. 拾光小说→听书→AI情感听书→导入电脑配对文件→测试连接→选择风格→保存并开始AI听书。
4. 保持电脑和助手窗口开启。结束后关闭窗口或Ctrl+C。

配对JSON含私有密钥，不要公开分享。手机导入与测试连接不发送正文，明确开始AI后才发送当前及预取的短段正文。多网卡或自定义安装位置可以运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-Voice.ps1 -Lan -CosyVoiceRoot 'D:\AIsoft\CosyVoice' -Address '192.168.1.20'
```

示例IP需替换为自己的电脑私网地址。端口默认8765，可用Port修改；PrepareOnly仅生成配对文件。默认不带Lan只监听127.0.0.1，手机不能通过它访问电脑。局域网连接失败时检查IP、访客网络隔离与防火墙；脚本不会自动修改防火墙。端口被占用时在模型加载前拒绝。

目前一条合成女声，四种风格是模型指令，不能保证每一句的人名读音、情感或长篇一致性达到真人有声书水平。模型速度取决于电脑；关闭电脑后AI不可用，手机系统听书仍可独立使用。私网HTTP只适合自己的可信局域网，跨网络需要自行配置有效HTTPS。遵守CosyVoice及模型各自许可证。

## 研究工具

本机运行样本（按自己的安装位置修改路径）：

```powershell
& 'D:\AIsoft\CosyVoice\.venv\Scripts\python.exe' .\tools\voice-lab\probe.py --cosyvoice 'D:\AIsoft\CosyVoice' --output 'D:\ShiguangBuild\voice-lab'
```

探针创建 synthetic-reference.wav、happy.wav、sad.wav 与 report.json。参考音色已存在时不重新生成，便于对照同一音色；仅代表短样本推理，不代表整本书质量。

启动本机服务：

```powershell
& 'D:\AIsoft\CosyVoice\.venv\Scripts\python.exe' .\tools\voice-lab\server.py --cosyvoice 'D:\AIsoft\CosyVoice' --reference 'D:\ShiguangBuild\voice-lab\synthetic-reference.wav' --key-file '.\.tools\voice-server.key'
```

上述直接调用server.py默认仅监听127.0.0.1:8765，适合电脑自测；手机正常使用应采用前面的Start-LAN-Voice.cmd。启动生成随机设备密钥，不打印密钥；不要把运行时.runtime、密钥或配对文件放进源码ZIP、APK或公开仓库。脚本不会自动开放公网。

接口：

- `GET /health`：模型状态、已支持风格与音色。
- `POST /v1/speech`：JSON字段 text、style、voice、speed；返回 audio/wav。
- 两个接口都要求 Authorization: Bearer 设备配对密钥。
- style 支持 natural、happy、sad、angry；voice 当前仅 synthetic-female。
- text 限1—280字符；speed 限0.5—2；单请求推理，忙时返回429。

`check_server.py` 验证本机服务和实际 WAV，不向外部语音供应商发请求；`check_audio.py` 可用已缓存 Whisper 模型做内容转写检查。两者不证明听感或情绪自然度。

`offline_support.py` 同时限制 ModelScope 的模型下载行为；缺少必需权重应先补齐资源，不能把 Hugging Face 离线环境变量当作全进程断网。正式模型日志中的正文会被替换成概括性条目，HTTP访问日志关闭。

实验结束后关闭服务可以释放显存。不要同时启动多个实例争用端口或让多个服务并发操作同一模型。
