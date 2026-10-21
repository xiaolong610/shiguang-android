# 构建与验证

## 当前版本

- 版本：0.8.0，versionCode 8。
- Android 8.0（API 26）及以上；compileSdk / targetSdk 为 36。
- 包名：`app.shiguang.mobile`。
- 发布 APK 与此前 0.6.0、0.7.0 使用相同签名，可保留数据覆盖安装。

## 本地构建

准备 JDK 21、Android SDK 36、Build Tools 36.0.0、NDK 27.2.12479018 与 CMake 3.22.1；Gradle Wrapper 使用 9.2.0。配置 `JAVA_HOME` 和 `ANDROID_HOME`，或在未跟踪的 `local.properties` 中设置 SDK 路径。

Windows PowerShell：

```powershell
.\build.ps1 -Test
```

也可以使用 Gradle Wrapper：

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。源码中的 `third_party/libmobi` 为本地电子书解码所需源码，应一并检出。签名私钥不存入仓库；自行构建的调试签名取决于构建机器，覆盖已安装版本需要相同签名。

## 已完成的 0.8.0 验证

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 74 项通过 |
| Android 35 模拟器功能检查 | 44 项通过；另有 1 项仅在系统大字号运行的旧布局用例在普通字号条件下跳过 |
| 新增交互 | 收藏切换、小说/漫画/视频详情排序、章节原始索引保持 |
| 阅读 | 小说滑动/点击/滚动切换、重建恢复位置、漫画左右翻/日漫右翻与重新打开、旧版漫画进度迁移 |
| 分页完整性 | 在小窗口和大字号下遍历全部页面，校验文字和表情字符完整、无重复丢失 |
| 原有功能回归 | 听书、后台播放、画中画、动态嗅探、订阅、263 条规则导入、多格式本地小说与 100MiB 上限 |
| 安装包 | 签名验证和原生库 16KiB 对齐检查通过 |

这是模拟器验收记录；手机实际站点可用性和不同厂商后台限制仍需在目标设备验证。Releases 中提供 APK、源码快照、页面截图、规则 JSON 与 SHA-256 校验文件。

## 仓库范围

包含 App 源码、必要的第三方源码与许可、构建配置、测试素材和文档。构建缓存、本机路径配置、运行日志、模型权重、语音配对文件、密钥和签名私钥均不跟踪。语音助手的模型与运行环境按 `tools/voice-lab/README.md` 单独准备。
