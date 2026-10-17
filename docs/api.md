# API 合约

默认 Java 地址 `http://127.0.0.1:8080`，开发前端 `http://127.0.0.1:5173`。时间为 UTC ISO-8601 字符串，上课日期为 `YYYY-MM-DD`。错误响应是 `{"message":"中文说明"}`。

## Java API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 在线状态、demo 和版本 |
| GET | `/api/settings` | 配置，不返回明文凭证 |
| PUT | `/api/settings` | 保存配置字符串键值，凭证留空保留；校验失败整次回滚 |
| POST | `/api/settings/system-prompt/reset` | 立即恢复并保存默认系统提示词，其他设置不变 |
| GET | `/api/recordings` | 全部录音，支持 q、course、category、date、notesOnly |
| POST | `/api/recordings` | multipart 上传：file、title、course、recordedOn、category |
| GET | `/api/recordings/{id}` | 录音、当前笔记/转录、所有转录历史、任务、日志和笔记版本 |
| PUT | `/api/recordings/{id}/course` | 更改录音及其笔记的课程归档，保留已有内容和 Notion 页面关联 |
| GET | `/api/recordings/{id}/audio` | 播放原音频，支持 HTTP Range |
| GET | `/api/recordings/{id}/artifacts` | 每次调用的原始响应与生成 Markdown |
| GET | `/api/notes/{id}/chat` | 最近 40 次 AI 对话，包含任务状态、回复和关联版本 |
| POST | `/api/notes/{id}/chat` | 发送问题或修改要求，返回 202 与持久化对话任务 |
| POST | `/api/recordings/{id}/jobs` | 创建独立处理任务 |
| PUT | `/api/notes/{id}` | 保存新笔记版本，必须携带读取时的 revision |
| POST | `/api/notes/{id}/confirm` | 确认指定 revision |
| GET | `/api/notes/{id}/markdown` | 下载当前 Markdown |
| POST | `/api/notes/{id}/reset-notion-creation` | 用户核对远端未创建后，清除不确定创建状态 |

移动课程请求：

```json
{"course":"高等数学","expectedCourse":"线性代数"}
```

两项均必填，课程名称去除首尾空白后保存，上限 120 字符。返回更新后的 `Recording`。录音、笔记及转录 ID 不变，不新增笔记版本、不撤销笔记确认，也不发送模型或 Notion 请求。原来已同步的录音改为 `syncStatus=PENDING`，保留 `syncedRevision` 和页面关联；再次同步更新同一页面的 Course 字段。失败同步的状态、错误及不确定创建标记保留，继续使用原有核对与重试流程。

有排队或运行中的任务时返回 409。读取时的 `expectedCourse` 与当前课程不符也返回 409；目标已是当前课程时幂等返回成功，不重复记日志。不存在返回 404，无效课程返回 400。

转录进度：

录音列表以及详情中的 `recording` 在 0.2.1 增加 `transcriptionProgress`：

```json
{
  "phase": "transcribing",
  "message": "正在识别课堂录音",
  "percent": 80,
  "processedMs": 54000,
  "durationMs": 67392,
  "completedChunks": 2,
  "totalChunks": 3
}
```

阶段包括 `queued`、`starting`、`converting`、`loading_model`、`transcribing`、`finalizing`、`completed`、`failed`。准备阶段的 `percent` 明确为 `null`；识别按音频覆盖时长计算，只有 SQLite 提交成功后才是 100。失败保留本次已完成进度，新尝试读取新的任务目录。Worker 的原子快照位于 `asr/<jobId>/progress.json`；读取进度异常不影响录音查询。

创建任务请求：

```json
{"stage":"TRANSCRIBE"}
```

`stage` 为 `TRANSCRIBE` / `SUMMARIZE` / `REFINE` / `SYNC`。`REFINE` 用于重试最近的 AI 对话要求，首次对话通过 `/notes/{id}/chat` 发送。返回 202；活跃任务冲突、未转录或笔记未确认返回 409。演示模式拒绝 SYNC。

AI 对话请求为 `{"message":"保留全部公式，只精简重复说明","revision":2}`。`message` 为 1–5000 字符，基于当前笔记版本；版本冲突返回 409，不会丢失输入内容。AI 修改成功时保存 `AI_CHAT` 版本并撤销旧确认；仅回答问题且未改动正文时保留版本与确认。已有笔记被人工修改后，不覆盖新版本，候选全文及原始模型响应保留。

设置中的 `llmSystemPrompt` 为 1–20000 字符，默认采用内置课堂提取提示词；提取和对话任务在开始时读取一次。AI 对话另附精确替换 JSON 协议，无须把它写进自定义提示词。

保存笔记：

```json
{
  "title":"导数与连续性",
  "markdown":"## 核心概念\n可导一定连续。",
  "category":"重点复习",
  "revision":1
}
```

返回新笔记（revision 自增、confirmedRevision 清空）。`markdown` 最长 2,000,000 个 UTF-16 字符，与 AI 生成保存上限一致。旧 revision 返回 409，不覆盖任何新数据。同步期间保存返回 409。

原始结果新增 `SUMMARY_PART`（每段知识及段号），`GENERATED_MARKDOWN` 为组装后的完整文档；`LLM_RESPONSE` 继续保留模型原始响应。长课堂不要求把所有分段反复压缩成一个模型上下文。

确认 / 重置不确定创建：

```json
{"revision":2}
```

## Python Worker

默认地址 `http://127.0.0.1:8090`。设置 `ASR_WORKER_TOKEN` 后请求需携带 `Authorization: Bearer <token>`。

`GET /health` 返回服务状态、模型依赖是否安装、模型是否已加载和时间戳类型。服务在线不表示模型已下载或已经验证。

`POST /transcribe`：

```json
{
  "inputPath":"D:\\AiProducts\\class-record-ai-note\\data\\local\\audio\\<录音UUID>.m4a",
  "jobId":"<任务UUID>"
}
```

路径必须处于 `DATA_DIR/audio` 内（解析后检查，拒绝路径穿越）。每次重试使用新 job ID；相同 job ID 已有成功结果时直接返回缓存，不再推理。

响应：

```json
{
  "provider":"sensevoice-small",
  "durationMs":5600,
  "text":"课堂文本",
  "segments":[
    {"startMs":0,"endMs":5600,"text":"课堂文本","timestampKind":"window"}
  ],
  "raw":{
    "journal":"<绝对路径>/chunks.jsonl",
    "normalizedAudio":"<绝对路径>/normalized.wav",
    "timestampKind":"window",
    "windowSeconds":28,
    "overlapSeconds":2,
    "dedupe":true
  }
}
```

输入边界时间不会冒充词级对齐。失败 HTTP 详情包含 `artifactPath`，可查已经写出的 raw journal / error.json。
