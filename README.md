# 课记 · 课堂录音 AI 笔记系统

一个面向个人本机使用的 MVP：上传课堂录音，本地转录，提取课堂知识，编辑确认后同步到 Notion。

**Java 25 / Spring Boot 3.5.16 / Vue 3 + TypeScript / Python 3.12 / SenseVoice-Small / SQLite**

## 桌面版（Windows 64 位）

已提供 **课记 0.2.4** 桌面程序。打开 `artifacts/desktop/ClassNotes-Setup-0.2.4-x64.exe` 安装或覆盖更新，再从桌面或开始菜单启动「课记」。安装包包含 Java、Python、FFmpeg 与 CPU 语音运行环境，使用时无须另装开发工具。首次转录会下载约 936 MB 语音模型。

上传弹窗显示实际传输进度；录音列表和详情显示转录进度、已识别时长及分段数量。准备模型时显示当前阶段，保存成功后才显示完成。[0.2.1 更新说明](docs/releases/0.2.1.md)。

0.2.2 修复长课堂的分段摘要反复合并失败：逐段提取后按授课顺序保留完整笔记，只有能放进一次请求时才尝试全文合并。长笔记可保存和编辑至 200 万字符。[0.2.2 更新说明](docs/releases/0.2.2.md)。更新后在失败记录点击「知识整理 → 重试」，可复用已有转录。

0.2.3 在笔记工具栏加入「AI 对话」：可连续说明修改要求、保留规则或提问，精确修改自动保存新版本；失败保留现有笔记。系统设置可编辑 System Prompt 并一键恢复默认。[0.2.3 更新说明](docs/releases/0.2.3.md)。

0.2.4 支持「移动课程」：在录音列表点击「移动」，或在录音详情点击「移动课程」，选择已有课程或输入新课程。原音频、转录、笔记、版本与 AI 对话一起保留；已同步的笔记再次同步会更新原 Notion 页面。[0.2.4 更新说明](docs/releases/0.2.4.md)。

录音、笔记和密钥保存在 `%APPDATA%\ClassNotes\data`，模型和运行日志也放在独立的用户目录。覆盖安装及普通卸载保留这些资料；数据库升级先备份，旧程序遇到较新的数据格式会拒绝打开。

「系统设置 → 桌面版与更新」可打开资料目录、导入旧网页版本数据、检查和下载更新。自动更新需要发布者提供实际的 HTTPS 更新目录；当前没有预设线上发布站点，也可直接运行新版本安装包升级。当前安装包未做代码签名。

详见 [桌面版使用与发布](docs/desktop.md) 和 [桌面版验证记录](docs/desktop-verification.md)。

## 源码启动（Windows PowerShell）

在项目根目录执行。需要 Java 25、Node.js 22.12+ 和 Python 3.11/3.12。Maven 不在 PATH 时，安装脚本将校验并下载到 `.tools/`。FFmpeg 由 `imageio-ffmpeg` 提供，无须全局安装。

```powershell
# 首次安装；替换为你自己的 Python 3.12 路径
.\scripts\setup.ps1 -PythonPath 'C:\Python312\python.exe' -WithAsr

# 构建前端、运行检查、打包 Spring Boot JAR
.\scripts\build.ps1

# 正式模式：Java 8080，Worker 8090
.\scripts\start.ps1
```

打开 <http://127.0.0.1:8080>，在「系统设置」中填写模型 API 地址、模型名称及 API Key。上传 M4A/WAV 后即可开始转录。没有配置大模型时，转录仍可完成，配置后点击「整理笔记」。

SenseVoice 首次使用会下载约 936 MB 模型权重；CPU 版 PyTorch 已由安装脚本安装。之后可使用本地缓存。`ASR_MODEL` 可指定预先下载的本地模型目录。

### 不安装语音模型，先体验界面

```powershell
.\scripts\setup.ps1 -PythonPath 'C:\Python312\python.exe'
.\scripts\build.ps1
.\scripts\start.ps1 -Demo
```

演示模式使用固定转录与固定笔记，示例音频为静音；上传自己的录音也不会分析其内容。界面明确显示「演示模式」，并禁用 Notion 写入。演示数据在 `data/demo`，正式数据在 `data/local`。

可以同时查看两种模式：另一个终端执行 `scripts/start.ps1 -Port 8081` 启动正式模式。关闭启动脚本 / Ctrl+C 可停止该次服务；启动脚本管理它创建的 Worker。Java 从运行副本启动，避免 Windows 锁住构建产物。

## 使用流程

1. **上传录音**：填写标题、课程、上课日期和分类，支持非空 M4A/WAV，单文件最大 1GB。后端检查扩展名与音频文件头，原始文件使用 UUID 保存。
2. **本地转录**：FFmpeg 转为 16kHz 单声道 PCM16，SenseVoice 分段识别。状态会自动刷新；可查看原始文本、历史转录与时间轴，点击时间戳回听。
3. **知识整理**：分段提取概念、公式、定理、适用条件、证明要点、解题思路和易错点，再分层合并。要求忽略没有知识含量的板书动作，无法核实的内容标记「待核对」。
4. **编辑笔记**：Markdown + LaTeX 预览；可以修改正文、标题和分类，导出 `.md`。每次保存都会新增版本，可查看旧版本或恢复到编辑器后另存。编辑草稿保存在浏览器本地。
5. **检索归档**：搜索覆盖标题、课程及整篇 Markdown；可按课程、分类和上课日期筛选，笔记库提供日期归档索引。
6. **确认与同步**：先确认当前版本，再同步到指定 Notion 数据源。任何正文修改或重新整理会撤销确认，需要重新核对。

## Notion 配置

使用官方 [Integration](https://www.notion.so/profile/integrations) Token，给目标数据库添加连接，并开启读取、插入和更新内容权限。此程序直接使用官方 REST API，无须 Codex 的 Notion 插件。

在 Notion 建立以下字段（名称可在设置中映射）：

| 默认字段名 | Notion 类型 | 用途 |
|---|---|---|
| `Name` | Title（标题） | 笔记标题 |
| `Source ID` | Text（富文本） | 固定 `classnotes:<笔记UUID>`，用于查重 |
| `Course` | Text（富文本） | 课程 |
| `Date` | Date（日期） | 上课日期 |
| `Category` | Select（单选） | 笔记分类 |

填写数据库 UUID 或数据源 UUID。仅有一个数据源时可从数据库自动解析；多个数据源必须指定数据源 ID。连接和字段错误会明确记录，不会猜测目标表。

同步使用 `Notion-Version: 2026-03-11`，先查询 `Source ID`，再创建或复用页面；创建成功后立即持久化页面 ID，正文使用 Markdown `replace_content` 写入。同一页面的正文由课记管理，重同步会更新为本机已确认版本。请将额外的 Notion 编辑内容保存在其他页面；子页面和子数据库仍受 Notion 的默认删除保护。

若创建调用失败且结果不确定，会保留创建意图。后续重试先查重，未查到页面时不会盲目再创建。先按 `Source ID` 核对数据库：存在则重试复用，不存在才使用「核对后重置创建状态」。

## 配置与存储

网页配置保存在 SQLite；API Key / Token 使用 AES-GCM 加密，网页读取配置只返回「是否已配置」，留空保存时保留原凭证。密钥在对应数据目录的 `settings.key`。网页配置优先于启动环境变量。

也可在启动前设置环境变量：

| 变量 | 默认值 / 说明 |
|---|---|
| `LLM_BASE_URL` | `https://api.openai.com/v1`，兼容 Chat Completions 的基础 URL，需包含 `/v1` |
| `LLM_MODEL` | 无默认模型；填写服务商的准确模型 ID |
| `LLM_API_KEY` | API Key，本地免密服务可不填 |
| `NOTION_TOKEN` | Notion Integration Token |
| `NOTION_DATABASE_ID` | 数据库 UUID |
| `NOTION_DATA_SOURCE_ID` | 数据源 UUID，多数据源数据库必须填写 |
| `ASR_WORKER_TOKEN` | 可选，Java 与 Worker 必须一致；可在网页填写相同 Worker Token |
| `ASR_MODEL` | `iic/SenseVoiceSmall`，也可填写本地模型目录 |
| `ASR_DEVICE` | `cpu`；CUDA 环境可设 `cuda:0` 并自行安装匹配的 GPU PyTorch |
| `ASR_LANGUAGE` | `auto`，也可设 `zh` / `en` / `yue` / `ja` / `ko` |
| `ASR_HUB` | `ms`；使用 `hf` 时需同时设置对应 Hugging Face 模型名 |
| `ASR_WINDOW_SECONDS` | `28`，不得超过 30 秒 |
| `ASR_OVERLAP_SECONDS` | `2`，小于分段长度 |
| `ASR_DEDUPE` | `true`，全文合并时仅去掉相邻边界完全相同的重复文本；原始分段始终保留 |
| `ASR_ENGINE_MODULE` | 可选，替换 ASR Engine，见 [扩展说明](docs/architecture.md) |
| `MODELSCOPE_CACHE` / `HF_HOME` | 启动脚本默认放在项目 `.tools/` 下，可使用现有缓存 |
| `FFMPEG_PATH` | 可选，指定 FFmpeg 可执行文件；否则自动查找 PATH / bundled FFmpeg |

直接启动进程时（不使用脚本），Java 与 Worker 应设置同一**绝对路径** `DATA_DIR`。Java `PORT` 默认 8080，`ASR_WORKER_URL` 默认 `http://127.0.0.1:8090`。启动脚本以 `-Port` / `-WorkerPort` 指定端口，并自动设置上述值。

```text
data/local/ 或 data/demo/
  classnotes.db        SQLite（WAL，外键，持久化队列）
  settings.key         本机凭证密钥，备份时必须保留
  backend.lock         数据目录进程锁
  audio/<UUID>.m4a     原始音频
  asr/<任务UUID>/
    normalized.wav     规范化音频
    chunks.jsonl       逐段原始结果，写入并刷新后再合并
    result.json        成功结果
    error.json         失败信息（成功任务没有此文件）
```

数据库保存所有转录版本、AI/人工笔记版本、每次 HTTP 原始响应和处理日志。原始音频和上述结果没有自动删除策略。备份时停止后端与 Worker，再复制整个数据目录（包含 `settings.key`；运行时备份需使用 SQLite 在线备份 API）。

## 可靠性与当前边界

- 转录、总结、同步是独立任务；失败后从页面重试对应阶段，不必重新转录。单录音只允许一个活跃任务。
- 后端重启会将中断的 `RUNNING` 任务标为失败，保留 `QUEUED` 任务并继续处理。一个数据目录只能有一个后端进程。
- HTTP 原始响应先保存再解析；只接受 LLM `message.content` 正式回答，忽略 `reasoning_content` 等字段，拒绝截断回答或含推理标签的正文。
- 长文本分段不截掉尾部，每段知识单独留存；超长笔记按授课顺序组装，不再反复压缩摘要。一次全文合并发生截断或过度扩写时，保留完整分段笔记。网络、鉴权等错误仍显式失败，可独立重试。
- 编辑有版本检查；整理期间发生人工编辑时，生成结果不会覆盖新内容；同步期间暂不允许保存新版本。重新转录成功后保留旧笔记，但撤销原确认，要求重新核对。
- **时间戳是输入分段的开始/结束边界，存在重叠，精度为分段级。** 目前未接入强制对齐、词级时间戳或说话人分离。固定分段在切点附近可能影响识别，请通过音频回听核对。
- CPU 能真实识别，不要求独立显卡；课堂远场、噪声、公式口述等识别质量仍需用你的课堂录音评估。
- 这是本机单用户 MVP，仅绑定 `127.0.0.1`，未实现账号、多人权限和公网部署。网页不加载外部字体；语音模型首次下载后可本地使用，LLM 与 Notion 仍需要联网。
- 本地转录不发送音频给大模型。知识提取会将转录文本发送到你配置的模型 API，Notion 同步发送已确认笔记及归档字段。

## 开发与验证

```powershell
# 前端热更新（另一个终端，Java 使用 8080）
cd frontend
npm.cmd run dev

# Java 检查
..\scripts\maven.ps1 test

# Worker 检查（从 worker/ 执行）
.\.venv\Scripts\python.exe -m pytest -q

# 直接转录一个文件（输出目录须尚不存在）
.\.venv\Scripts\python.exe cli.py 'D:\Audio\lecture.m4a' --output '..\data\asr\manual-run-001'

# 真实模型检查：下载模型后用模型附带的公开样例识别
.\.venv\Scripts\python.exe smoke_real_asr.py

# 跨窗口真实检查：公开样例重复约 67 秒，生成 3 个窗口
.\.venv\Scripts\python.exe smoke_real_asr.py --repeat 12
```

首次安装和构建会访问官方 Maven、npm、PyPI 和 PyTorch 仓库；需要允许网络访问及执行本地开发工具。测试使用临时 SQLite、本地 HTTP 模拟服务与生成的音频，不写入真实 Notion，不使用付费 LLM。

详细内容：[架构与替换接口](docs/architecture.md)、[API](docs/api.md)、[本次验证记录](docs/verification.md)。

## 官方接入依据

- [Spring Boot 3.5 / Java 25 兼容性](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [SenseVoice 官方仓库与长录音分段](https://github.com/QwenAudio/SenseVoice)
- [Notion 数据源查询 API](https://developers.notion.com/reference/query-a-data-source)
- [Notion Markdown 内容 API](https://developers.notion.com/guides/data-apis/working-with-markdown-content)
