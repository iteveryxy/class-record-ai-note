# ClassNotes · AI Lecture Notes

[简体中文](README.md) | **English**

ClassNotes is a local, single-user desktop app that turns lecture recordings into editable study notes. Upload audio, transcribe it locally, extract key ideas with your chosen model API, then review and sync confirmed notes to Notion.

**Java 25 · Spring Boot 3.5.16 · Vue 3 + TypeScript · Python 3.12 · SenseVoice-Small · SQLite · Electron**

## Windows desktop app

The current version is **0.2.6**, for **Windows x64**. Run `ClassNotes-Setup-0.2.6-x64.exe` from the release assets, or find a locally built installer under `artifacts/desktop/`.

The installer includes Java, Python, FFmpeg and the CPU speech runtime. End users do not need to install development tools. The first transcription downloads approximately **936 MB** of SenseVoice model weights. Subsequent runs reuse the local cache.

Save your work and quit ClassNotes before installing an update. Install over the existing version; you do not need to uninstall first. The installer is currently unsigned.

## What it does

- **Recording library:** upload M4A or WAV files, organize them by course and lecture date, and search recordings and full note content.
- **Local transcription:** automatic audio conversion, overlapping segments for long recordings, segment timestamps, progress indicators and transcript history.
- **Knowledge extraction:** use a configurable Chat Completions compatible API to extract concepts, formulas, theorems, conditions, solution steps and common mistakes.
- **Long lecture notes:** preserve all extracted sections in lecture order when a single consolidation request is too large. Notes support up to 2 million characters.
- **Markdown editing:** preview Markdown and LaTeX, export `.md` files and restore previous versions through the editor.
- **AI note chat:** ask questions or request specific changes while preserving other content. Successful edits create a new note version; invalid or conflicting edits leave the current note intact.
- **Editable System Prompt:** customize extraction and chat instructions, or restore the built-in prompt without changing other settings.
- **Move between courses:** move a recording together with its transcripts, notes, versions and chat history without reprocessing the audio.
- **Notion sync:** review and confirm a note before syncing. A stable Source ID lets retries update the same page instead of creating duplicates.
- **Chinese and English interface:** choose Simplified Chinese, English or the system language in Settings. The choice applies immediately and is remembered.
- **Storage controls:** migrate recordings, transcripts, notes, history, encryption key and model cache to an empty folder. Copies are verified before switching, and the original folder stays as a backup.
- **Optional audio retention:** disable keeping audio after successful transcription. Failed transcriptions keep their audio for retries. Review and delete existing audio separately from each recording’s details.

Interface language is separate from your content. Changing it does not translate course names, recordings, transcripts or notes, and does not rewrite the System Prompt. To request notes in English, edit the System Prompt accordingly.

## First use

1. Open **Settings → Interface language** to choose a language if needed.
2. Under **AI knowledge extraction**, enter the API base URL, model ID and API key. Include `/v1` when required by your provider. A local model without authentication may leave the key blank.
3. Upload a non-empty M4A or WAV recording, up to **1 GB**, and enter its title, course and lecture date.
4. Follow transcription progress. The audio is converted to 16 kHz mono PCM16 and transcribed locally. Transcription can finish before an LLM is configured; extract notes later after adding your model settings.
5. Review and edit the notes. Use **AI chat** for questions or precise revision requests. Saved edits remain available in version history.
6. Confirm the current note version, then sync it to Notion if configured.

If a recording is in the wrong course, click **Move** in the recording list or **Move to course** in its details. Choose an existing course or enter a new one. For a note already synced to Notion, sync again afterward to update the original page's course field.

## Notion setup

Create a Notion integration, grant it read, insert and update access, and connect it to the target database. This app calls the official Notion API directly; no Codex connector is required.

Create these database properties, or change the mappings in Settings:

| Default property | Notion type | Purpose |
|---|---|---|
| `Name` | Title | Note title |
| `Source ID` | Text / rich text | Stable `classnotes:<note UUID>` identifier |
| `Course` | Text / rich text | Course name |
| `Date` | Date | Lecture date |
| `Category` | Select | Note category |

Enter the integration token and database ID or data source ID in Settings. A database with one data source can be resolved automatically; databases with multiple data sources require an explicit data source ID.

Sync uses the locally confirmed note as the source of truth. Syncing again replaces the managed page's Markdown content and updates its archive fields. Keep unrelated manual Notion content on a separate page.

If page creation has an uncertain result, the app preserves the creation intent and blocks blind retries. Search the database by Source ID first. Retry to reuse an existing page, or reset creation status only after confirming that no page exists.

## Data, privacy and backups

By default, desktop data is stored separately from program files under `%APPDATA%\ClassNotes`:

```text
ClassNotes/
  data/
    classnotes.db     Recordings, notes, tasks, settings and history
    settings.key      Credential encryption key; back up with the database
    audio/            Original recordings
    asr/              Segment results and raw transcription output
    backups/          Database and key backups made before schema upgrades
  models/             Downloaded speech models
  logs/               Local service and update logs
  desktop.json        Update URL and interface language preference
```

Open the folder from **Settings → Desktop app and updates → Open data folder**. Quit the app before copying the entire folder for a backup. Keep `settings.key` together with the database so saved credentials remain usable.

Normal updates and uninstall retain user data and model caches. Database migrations make backups before changing the schema; older app versions refuse to open newer data formats. The current data schema is **v3**.

Audio transcription runs locally. Knowledge extraction and AI chat send relevant text to the model API you configure. Notion sync sends the confirmed note and archive fields to Notion. These remote features require network access. API keys and tokens are encrypted at rest and are not returned in plaintext by the settings API.

### Change storage location and audio retention

In **Settings → Storage and recordings**, click **Change location and migrate** and select an empty folder. Save settings and drafts and wait for all uploads and processing tasks first. The app copies `data/` and `models/`, checks file hashes and the database, then starts local services in the new location before saving the switch. The original folder remains as a backup.

Desktop preferences, the storage pointer, browser drafts and diagnostic logs stay under `%APPDATA%\ClassNotes`. Keep this folder. After checking the new location, you may remove the old `data/` and `models/` yourself. An unavailable drive or mismatched storage identity prevents startup instead of creating an empty workspace. Reconnect the drive or restore `desktop.json` from backup. Use **0.2.6 or later** after migrating; older versions do not understand the custom location.

**Keep local audio after successful transcription** is enabled by default. Turn it off and save settings to delete uploaded audio and transcription audio caches after subsequent successful transcriptions. Failed tasks retain audio for retries; existing recordings are unaffected. To delete an existing recording’s audio, open its details and use **Delete local audio**, then review and confirm. Text, timestamps, raw text output, notes and history remain. Playback and retranscription become unavailable. Your original upload source and old migration backups are unaffected.

## Updates

Use a newer installer for manual updates. In-app updates require a real HTTPS update directory configured in **Settings → Desktop app and updates**. No public update server or GitHub repository is preconfigured.

A release contains three matching files:

```text
ClassNotes-Setup-<version>-x64.exe
ClassNotes-Setup-<version>-x64.exe.blockmap
latest.yml
```

Publish all three together. The app checks the release's data compatibility range and installer SHA-512 before installation. Finish running tasks and save drafts before restarting to install.

## Run from source

Use Windows PowerShell with **Java 25**, **Node.js 22.12+**, and **Python 3.11 or 3.12**. Python 3.12 is used for the desktop speech bundle. Run these commands from the project root:

```powershell
# Replace this with your Python 3.12 executable.
.\scripts\setup.ps1 -PythonPath 'C:\Python312\python.exe' -WithAsr
.\scripts\build.ps1
.\scripts\start.ps1
```

Open `http://127.0.0.1:8080`. The default Worker port is `8090`. The setup script downloads Maven into `.tools/` if needed and installs CPU PyTorch and speech dependencies. FFmpeg is supplied through `imageio-ffmpeg`.

To try the interface without installing the speech model:

```powershell
.\scripts\setup.ps1 -PythonPath 'C:\Python312\python.exe'
.\scripts\build.ps1
.\scripts\start.ps1 -Demo
```

Demo mode returns fixed transcript and note examples, uses silent sample audio, and disables Notion writes. It does not analyze uploaded recordings. Demo data uses `data/demo`; normal mode uses `data/local`.

For frontend development, run `npm.cmd run dev` in `frontend/` while the backend runs. `scripts/build.ps1` checks and builds the frontend, packages the backend and runs Worker tests. Desktop tests run with `npm.cmd test` in `desktop/`.

## Build the Windows installer

Set up the full speech environment first, then install desktop packaging dependencies:

```powershell
cd desktop
npm.cmd ci
cd ..
.\worker\.venv\Scripts\python.exe -m pip install PyInstaller==6.22.3
.\scripts\release-desktop.ps1
```

The script builds the app, freezes the speech Worker, creates a Java runtime with `jlink`, packages the NSIS installer and generates update metadata. Output goes to `artifacts/desktop/`.

When changing versions, update the backend version, frontend and desktop package versions and lockfiles, and the backend's application version. Keep the app ID, installer GUID and user data directory stable for compatible upgrades.

Publish source code in a Git repository and installers as release assets. Local data, credentials, caches, dependencies and generated installers are excluded by `.gitignore`. Project source is licensed under MIT; third-party components retain their own licenses and notices.

## Push committed changes

Double-click `push.cmd` in the project root to push the current branch to its configured upstream, such as `origin/main`. The script can be launched from any folder and keeps its console open so you can read the result. Complete GitHub authentication if Git prompts for it.

The script pushes commits that already exist locally. Commit modified and new files first. It checks the remote branch before pushing, stops if the remote has newer commits, and does not force-push, merge, or create commits automatically. A push rejected by the server leaves local commits intact.

To check the push without changing the remote branch:

```powershell
.\scripts\push.ps1 -DryRun
```

## License

[MIT License](LICENSE), copyright (c) 2026 Yunxin Ye. The project license does not replace the licenses of bundled dependencies or downloaded models. Future desktop builds include the project license as `resources/LICENSE-ClassNotes.txt`.

## Current limits

- This is a local, single-user MVP, bound to `127.0.0.1`. There are no accounts, collaboration features or public server deployment support.
- **Timestamps are segment boundaries**, may overlap and are not word-level alignment. Speaker diarization and forced alignment are not implemented.
- Recognition quality depends on distance, noise, speech and spoken mathematical notation. Replay important passages to check them.
- Long notes and broad AI revision requests can require many model calls. The interface language does not change model instructions or recognition language.
- Transcription, extraction, AI revision and sync are separate persistent tasks. Retry a failed stage without discarding previous results. Only one task per recording can be active at a time.
- Raw responses are saved before parsing. Truncated model output, reasoning-marked output and conflicting edits are rejected rather than replacing a valid note.

Further documentation: [Architecture](docs/architecture.md), [API contract](docs/api.md), [Desktop deployment](docs/desktop.md) and [Release notes](docs/releases/0.2.6.md). These detailed documents are currently in Chinese.
