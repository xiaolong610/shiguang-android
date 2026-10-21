# 第三方组件说明

拾光为独立的 Android 客户端实现。自有代码尚未指定开源许可证；第三方组件、规则与素材遵循各自许可。

- **libmobi**：用于 MOBI / KF8 等电子书解析，源码随仓库保留于 `third_party/libmobi`；许可见 [COPYING](third_party/libmobi/COPYING)（LGPL-3.0），上游为 [bfabiszewski/libmobi](https://github.com/bfabiszewski/libmobi)。保留上游源码、版权与构建资料。
- **Kazumi 规则资料**：兼容测试与转换涉及的规则保留 [MIT 许可](third_party/licenses/KazumiRules-MIT-LICENSE.txt) 和 Predidit 的原始版权声明。规则兼容不表示复制 Kazumi App 实现。
- **Android / Compose / Media3 / WorkManager / AndroidX WebKit、Kotlin Coroutines、OkHttp、Jsoup、Coil 和 PDFBox-Android**：通过 Gradle 解析，依赖声明见 [app/build.gradle.kts](app/build.gradle.kts)；各组件的许可仍归各自权利人。
- **CosyVoice**：仅为可选的电脑语音服务运行依赖，模型、权重和 Python 环境未随仓库或 APK 上传；使用时按其各自许可准备。

内置漫画、小说和短视频为原创离线演示内容。网站规则只用于描述访问与解析方式；站点内容由对应站点提供。
