# MVP 验证记录

验证日期：2026-10-13，Windows，本机 Java 25.0.4.1 / Node.js 24.18.0 / Python 3.12.14。

## 构建与自动化检查

| 检查 | 结果 | 覆盖范围 |
|---|---|---|
| Java 编译与 Spring Boot JAR | 通过 | Java 25，前端静态资源打入 JAR |
| Java 集成检查 | 11 项通过 | multipart 上传、文件头验证、HTTP Range、队列去重、失败重试、保留旧转录、笔记版本、确认撤销、全文搜索、编辑冲突、重启恢复 |
| LLM 模拟服务 | 通过（包含在 Java 检查内） | 全量分段与分层合并、忽略推理字段、拒绝截断/推理标签、保留原始响应 |
| Notion 模拟服务 | 通过（包含在 Java 检查内） | 未确认笔记禁止同步、正文失败后复用同一 page、创建结果不确定时阻止重复创建、远端查重后恢复 |
| 前端 TypeScript + Vite | 通过 | 生产资源构建，Markdown / KaTeX 随包提供 |
| 前端检查 | 3 项通过 | HTML / JavaScript URL 注入防护、公式预览、长时长格式 |
| Python Worker 检查 | 7 项通过 | 真实 FFmpeg 转换 M4A/WAV、16k 单声道、重叠窗口边界、失败保留 journal、API 路径与 Token、成功缓存、模型并发锁 |
| Python 依赖检查 | 通过 | `pip check` 无不兼容依赖 |

总计 **21 项自动化检查**。测试 SQLite 使用独立临时目录，LLM / Notion 使用本机 HTTP 模拟服务。自动化检查不调用付费 API，不写入真实 Notion。

## 浏览器实际运行

已运行 Spring Boot 服务，并通过浏览器操作验证：

- 录音库、课程与日期筛选、笔记库和配置字段能显示。
- Markdown 正文和导数的 LaTeX 公式实际渲染。
- 编辑保存新增 v2、v3，原 v1 保留，保存撤销确认；确认当前版本后显示已确认。
- 历史版本列表能显示 AI 与手动保存来源。
- 搜索正文后部的「复习自测」仍能匹配笔记。
- 正式模式网页上传公开 WAV 样例成功，Java 队列调用 Python Worker，实际界面显示 `sensevoice-small`、一个分段和 00:05 音频时长。
- 默认窄窗口与桌面 1440px 断点已视觉检查，窄窗口录音表提供横向滚动，详情改为单列。

截图：

- [录音库](../artifacts/screenshots/recordings-desktop.jpg)
- [笔记与公式](../artifacts/screenshots/note-desktop.jpg)
- [版本历史](../artifacts/screenshots/versions.jpg)
- [正式模式真实转录](../artifacts/screenshots/real-asr.jpg)

## 真实 SenseVoice 运行

安装 `funasr==1.4.14` 与 `torch/torchaudio==2.9.1+cpu`，从官方 ModelScope 下载 `iic/SenseVoiceSmall`，使用其 `example/zh.mp3` 公开样例。使用 FunASR 内置实现，`trust_remote_code=False`。

1. **短音频**：FFmpeg 转换后 5616 ms，SenseVoice CPU 生成非空中文转录和一个窗口时间戳。命令行结果与正式网页转录结果均保存。
2. **跨窗口音频**：重复公开样例 12 次得到 67392 ms 音频，实际生成 3 个窗口：`0–28000`、`26000–54000`、`52000–67392` ms。每个窗口都保存模型原始响应和文本，最后一个窗口覆盖到音频结束。

本机证据（运行输出不纳入源码）：`artifacts/runtime/real-asr-result.json`、`real-asr-long-result.json`、`asr-long-smoke.log`；正式网页任务的文件位于 `data/local/asr/<任务ID>/`。

这些检查证明模型能运行以及分段/保存/时间轴链路成立，**没有衡量真实课堂噪声、远场、公式识别或词级对齐的准确率**。

## 尚需用户配置后验证

- 真实 LLM 服务：需在系统设置填写模型名称、API 地址与 API Key，随后用自己的课堂录音检查知识提取质量及模型上下文限制。
- 真实 Notion 数据库：需提供 Integration Token、数据库授权和对应字段。已验证请求协议与重试状态机，但本次没有创建或修改用户 Notion 页面。
- 小时级课堂录音：已验证有界分段逻辑与跨窗口真实识别，未做整小时课堂音频的持续运行测试。

## 已处理的环境问题

- Windows 沙箱的临时目录 / 本地异步 HTTP 权限限制：用项目内测试临时目录，并在正常本机执行环境完成检查。
- 正在运行的 JAR 会锁定 Maven 打包目标：启动脚本改为从单独运行副本启动，数据目录仍按模式固定。
- Python 3.14 不用于语音环境：使用独立的 Python 3.12 虚拟环境。
- 音频文件先由 FFmpeg 规范化，再通过 PCM NumPy 数组送入 FunASR，避免 PyTorch 2.9 文件解码依赖差异。

当前日志中的 Maven / Mockito 原生访问与动态 agent 提示、Starlette 的弃用提示未导致检查失败；启动脚本已为 SQLite 开启 Java 原生访问。

最终统一构建日志：`artifacts/runtime/final-build.log`。运行中的服务已从最终 JAR 副本重启；浏览器重载后演示数据和正式转录记录仍然存在，控制台未发现错误。

最终 JAR：`backend/target/class-notes-0.1.0.jar`，SHA-256 `C96AC131A2511E85308C22ABA0705D99F65DE7ED80BCFC441F31E31ABA831C20`。
