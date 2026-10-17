# 课记桌面版

## 安装与日常使用

`artifacts/desktop/ClassNotes-Setup-0.2.1-x64.exe` 是 Windows 64 位安装与更新包。双击后选择安装位置即可，默认安装到当前用户的程序目录，并创建桌面与开始菜单快捷方式。旧版可退出后直接覆盖安装。正常使用不需要 Java、Python、Node.js 或 Maven。

桌面程序提供可拖动标题栏、最小化、最大化和关闭按钮，自动管理本地服务，无须打开终端或浏览器。现有上传、转录、笔记编辑、检索和 Notion 功能继续使用。首次启动后，在「系统设置」填写实际使用的大模型 API 和 Notion 配置。第一次转录联网下载 SenseVoice 模型，之后复用缓存；桌面包内的语音运行环境使用 CPU。

当前版本没有代码签名证书。安装包约 445 MiB，主要包含桌面运行环境和本地语音依赖；模型权重单独下载，未重复放入安装包。

## 资料位置与卸载

默认用户目录是 `%APPDATA%\ClassNotes`，与程序安装目录分开：

```text
ClassNotes/
  data/
    classnotes.db          已保存录音、笔记、任务、设置与历史版本
    settings.key           API 凭证加密密钥，须与数据库一起备份
    audio/                 原始音频
    asr/                   原始分段与识别结果
    backups/               数据格式升级前的数据库和密钥备份
  models/                  语音模型缓存
  logs/                    桌面程序、后端、语音与更新日志
  desktop.json             更新地址
  Local Storage/           桌面编辑草稿等浏览器资料
```

在「系统设置 → 桌面版与更新」点击「打开资料目录」可查看。备份时先退出课记，再复制整个用户目录；仅复制数据库可能导致凭证无法解密。

卸载可使用 Windows「已安装的应用」或安装目录里的 `Uninstall ClassNotes.exe`。普通卸载移除程序、快捷方式和卸载登记，保留上述用户目录。重新安装后可以继续使用原资料。需要完全删除资料时，先自行备份，再在退出程序后手动删除用户目录。

关闭程序时，如有任务或未保存编辑会提示确认。已保存的数据和本地草稿保留，中断任务可在下次启动后重试。

## 导入原网页 MVP

1. 先在旧网页中保存需要迁移的编辑，关闭旧版 Java 和 Worker 服务。
2. 启动桌面版，在尚无录音的工作空间进入「系统设置 → 桌面版与更新」。
3. 点击「导入旧版数据」，选择包含 `classnotes.db` 的旧 `data/local` 文件夹。
4. 程序复制 SQLite、密钥、原始音频、识别结果和备份，再自动重新打开工作空间；源目录继续保留。

导入会检查数据格式、凭证密钥、源目录进程锁和文件路径。目标已有录音时禁止导入，避免覆盖资料；失败会保留或恢复原工作空间。旧网页浏览器中的未保存草稿不属于 SQLite，需先在旧网页保存。切勿混用演示数据和正式课堂数据。

## 升级与数据兼容

先保存编辑并退出课记，再运行新版本安装包覆盖安装。应用标识、安装器 GUID 和用户目录名称保持稳定，因此不会因程序版本改变另建工作空间。自定义到其他磁盘的安装目录也使用同一磁盘的临时目录替换旧程序，并兼容深层依赖文件的长路径。

当前 SQLite 数据格式为 v2。打开旧 v0/v1 数据时，先保存数据库快照和 `settings.key`，再在事务中执行迁移。迁移失败不会提交变更；无法解密的旧配置会明确报错。旧程序发现较新的数据库版本会拒绝打开，防止不兼容降级写入。备份不是自动降级功能，恢复旧版时须使用配套的升级前数据备份。

应用内更新为「检查 → 下载 → 重启并安装」，按用户点击执行。运行任务未结束或笔记草稿未保存时禁止安装。更新仅接受 HTTPS 发布目录、较新的软件版本以及明确的数据兼容范围，并校验安装包 SHA-512；当前尚未配置实际线上更新站点。

## 构建与发布

源码构建需 Windows x64、Java 25（包含 jlink）、Node.js 22.12+、Python 3.12 和已有 ASR 虚拟环境。首次安装桌面开发依赖：

```powershell
cd desktop
npm.cmd ci
# 若网络环境使 Electron 的安装下载未执行，可单独运行：
node node_modules/electron/install.js
cd ..
worker\.venv\Scripts\python.exe -m pip install PyInstaller==6.22.3
scripts\release-desktop.ps1
```

该脚本检查和构建原项目、冻结语音 Worker、生成裁剪 Java 运行时、打包 NSIS 安装器并生成更新清单。资源已准备时可用 `scripts\release-desktop.ps1 -SkipPrepare` 仅重新打包桌面壳。安装器测试脚本 `scripts\installer-smoke.ps1` 使用项目内临时资料；检测到既有课记安装时会跳过，以保护现有程序。

发布新版本时统一修改后端 POM、前端和桌面 `package.json` 的版本号，并更新各自依赖锁文件。变更数据格式时增加数据库迁移、更新兼容范围和对应检查。`prepare-desktop.ps1` 会拒绝打包后端与桌面版本不一致的资源。

将以下三个构建产物发布到同一 HTTPS 目录：

- `ClassNotes-Setup-<版本>-x64.exe`
- 同名 `.exe.blockmap`
- `latest.yml`

清单包含 `version`、`files`、`sha512`、`size`、`minimumSchemaVersion` 和 `dataSchemaVersion`。每次重新构建都会按最终安装包重新计算摘要和长度，不能沿用旧摘要。0.2.3 支持输入格式 v0 起，输出格式 v3，新增持久化笔记 AI 对话。升级前自动备份数据库和密钥，旧程序会拒绝打开新格式。发布时先上传完整安装器及 blockmap，再更新清单。

可让用户填写发布目录，也可复制 `desktop/build/release.example.json` 为 `release.json`，填入真实目录后重新准备资源并打包。没有发布地址时显示「尚未配置更新发布地址」，不会提供虚假的新版本。正式分发可在 Electron Builder 中配置代码签名证书并重新构建；本次未配置签名或上传发行文件。

安装器的扩展方式依据 [Electron Builder NSIS 文档](https://www.electron.build/nsis/)；同磁盘目录替换遵循 [NSIS Rename 规则](https://nsis.sourceforge.io/Reference/Rename)，长路径处理依据 [Windows 路径限制说明](https://learn.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation)。
