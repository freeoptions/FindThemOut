# FindThemOut 项目专属规则

本项目继承 Codex 已加载的全局 `AGENTS.md`（本机：`C:\Users\freez\.codex\AGENTS.md`）；以下保留项目专属规则。

- 本项目是 Android Gradle 项目，应用模块位于 `app`，使用项目自带 Gradle Wrapper。
- 核心场景涉及图片扫描、相似图片判断和结果展示；修改扫描逻辑时注意性能、进度、取消操作和大目录内存占用。
- 默认不替我执行 Android Studio/Gradle 构建；修改完成后提醒：“已经修改完，可以去 as 构建了”。
- 不要提交 `local.properties`、签名文件、密钥或真实用户图片数据。
