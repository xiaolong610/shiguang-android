# 拾光图标 · 拾光精灵

0.8.1 起使用用户选定的 E「拾光精灵」二次元图标：薄荷短发、暖金星形发夹、星光眼神与暖白围巾。

![拾光精灵](shiguang-fairy-master.png)

- 原稿：`shiguang-fairy-master.png`，1254 × 1254，不透明 PNG。
- App 使用与原稿逐字节相同的图片 `app/src/main/res/drawable-nodpi/ic_launcher_portrait.png`。
- Android 自适应图标提供独立前景和深绿色背景；前景四边各留六分之一空间，保证常规桌面裁切下人物面部清楚。
- 常规图标与圆形图标统一使用这张精灵头像；最终外轮廓由手机桌面决定。
- 图像由内置图像生成工具制作，用户已选择采用该方向；未指定任何现有动漫角色作为参照。

相关配置见 `app/src/main/res/mipmap-anydpi-v26/`、`drawable/ic_launcher_foreground.xml` 和应用清单。
