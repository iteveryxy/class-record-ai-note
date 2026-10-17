"""Run the bundled backend with a long synthetic transcript and local model stubs.

Does not use the user's data, real speech model, paid LLM, or Notion workspace.
"""
import argparse
import hashlib
import io
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, default=ROOT / "artifacts/desktop/win-unpacked/resources/bundled")
    parser.add_argument("--chat-ui", action="store_true", help="Also verify AI editing and System Prompt through Electron UI")
    args = parser.parse_args()
    resources = args.resources.resolve()
    if not resources.is_relative_to(ROOT):
        raise ValueError("Only project-owned bundled resources may be tested")
    version = json.loads((ROOT / "desktop/package.json").read_text(encoding="utf-8"))["version"]
    run = ROOT / ".tools" / ("long-summary-" + uuid.uuid4().hex)
    run.mkdir(parents=True)
    segments = [{"startMs": i * 360000, "endMs": (i + 1) * 360000,
                 "text": f"第{i + 1}段原文：" + "课堂原文，概念与条件需要保留。" * 200,
                 "timestampKind": "window"} for i in range(40)]
    segments[-1]["text"] += "课堂末尾不可遗漏😀"
    source = "\n".join(segment["text"] for segment in segments)
    successful_inputs, part_notes = [], []
    counters = {"asr": 0, "llm": 0, "chat": 0}
    custom_prompt = "你是课堂笔记助手。用简洁中文表达，必须保留全部公式、时间来源和未要求修改的内容。"
    chat_question_history = []

    class Models(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path == "/transcribe":
                counters["asr"] += 1
                response = {"provider": "synthetic-long-transcript", "durationMs": 14400000,
                            "text": source, "segments": segments, "raw": {"synthetic": True}}
            elif self.path == "/v1/chat/completions":
                counters["llm"] += 1
                prompt = request["messages"][1]["content"]
                # A first failed extraction checks independent retry and raw retention.
                finish, content = "length", "incomplete answer"
                if "<note_part>" in prompt:
                    counters["chat"] += 1
                    assert request["messages"][0]["content"].startswith(custom_prompt)
                    note_part = prompt.split("<note_part>\n", 1)[1].rsplit("\n</note_part>", 1)[0]
                    instruction = prompt.split("<user_request>\n", 1)[1].split("\n</user_request>", 1)[0]
                    edits = []
                    if "来源时间有什么用" in instruction:
                        assert "分段1尾部关键点" in prompt.split("</conversation_history>", 1)[0]
                        chat_question_history.append(True)
                        reply = "来源时间帮助你回听原始录音。本次只回答问题，保留笔记全部内容。"
                    elif "分段1尾部关键点" in note_part:
                        edits = [{"before": "分段1尾部关键点", "after": "课后重点"}]
                        reply = "已将指定文字改为课后重点，所有公式、来源时间和其他分段均保留。"
                    else:
                        reply = "此段保留原样。"
                    content = json.dumps({"reply": reply, "edits": edits}, ensure_ascii=False)
                    finish = "stop"
                elif counters["llm"] > 1:
                    match = re.search(r"请整理第 (\d+)/(\d+)", prompt)
                    assert match and "<notes>" not in prompt, "Long notes must not enter recursive merging"
                    index, total = map(int, match.groups())
                    successful_inputs.append(prompt.split("<transcript>\n", 1)[1].rsplit("\n</transcript>", 1)[0])
                    content = (f"## 核心概念\n分段{index}/{total}独有知识 [03:59:59]\n"
                               + "知识说明含前提。\n" * 600 + "\n$$a^2+b^2=c^2$$ [00:00:01]\n" + f"\n分段{index}尾部关键点")
                    part_notes.append(content)
                    finish = "stop"
                response = {"choices": [{"finish_reason": finish, "message": {
                    "content": content, "reasoning_content": "PRIVATE_REASONING_MUST_NOT_BE_NOTES"}}]}
            else:
                self.send_error(404)
                return
            encoded = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    model = ThreadingHTTPServer(("127.0.0.1", 0), Models)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    model_base = f"http://127.0.0.1:{model.server_port}"
    env = {**os.environ, "DATA_DIR": str(run / "data"), "APP_DEMO": "false", "APP_DESKTOP": "false",
           "PORT": str(port), "APP_API_TOKEN": "", "QUEUE_ENABLED": "true",
           "ASR_WORKER_URL": model_base, "ASR_WORKER_TOKEN": "",
           "LLM_BASE_URL": model_base + "/v1", "LLM_MODEL": "local-smoke-fixture", "LLM_API_KEY": "",
           "NOTION_TOKEN": "", "NOTION_DATABASE_ID": "", "NOTION_DATA_SOURCE_ID": ""}

    def request(path, method="GET", value=None, raw=None, content_type="application/json"):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8") if value is not None else raw
        req = urllib.request.Request(base + "/api" + path, data=body, method=method,
                                     headers={"Content-Type": content_type})
        with urllib.request.urlopen(req, timeout=20) as result:
            return json.loads(result.read())

    def wait_for(path, predicate, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                result = request(path)
                if predicate(result):
                    return result
            except (OSError, urllib.error.HTTPError):
                pass
            if process.poll() is not None:
                raise RuntimeError("Bundled backend exited unexpectedly")
            time.sleep(0.2)
        raise TimeoutError(f"Timed out waiting for {path}")

    jar = resources / "backend/class-notes.jar"
    with (run / "backend.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([str(resources / "java/bin/java.exe"), "--enable-native-access=ALL-UNNAMED",
                                    "-jar", str(jar)], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            health = wait_for("/health", lambda result: result.get("status") == "ok")
            assert health["version"] == version
            request("/settings", "PUT", {"autoSummarize": "false", "llmChunkChars": "2000"})
            audio = io.BytesIO()
            with wave.open(audio, "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(b"\0\0" * 1600)
            boundary = "long-notes-" + uuid.uuid4().hex
            body = bytearray()
            for key, value in {"title": "长课堂完整笔记验证", "course": "高等数学", "recordedOn": "2026-10-14"}.items():
                body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
            body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode())
            body.extend(audio.getvalue())
            body.extend(f"\r\n--{boundary}--\r\n".encode())
            recording = request("/recordings", "POST", raw=body, content_type="multipart/form-data; boundary=" + boundary)
            path = "/recordings/" + recording["id"]
            wait_for(path, lambda result: result["recording"]["transcriptStatus"] == "SUCCEEDED")
            request(path + "/jobs", "POST", {"stage": "SUMMARIZE"})
            failed = wait_for(path, lambda result: result["recording"]["summaryStatus"] == "FAILED")
            assert failed.get("note") is None
            request(path + "/jobs", "POST", {"stage": "SUMMARIZE"})
            done = wait_for(path, lambda result: result["recording"]["summaryStatus"] in ("SUCCEEDED", "FAILED"))
            assert done["recording"]["summaryStatus"] == "SUCCEEDED", done["recording"].get("lastError")
            note = done["note"]
            assert len(source) > 100000 and len(note["markdown"]) > 250000
            for part in part_notes:
                assert part in note["markdown"]
            expected = "".join(f'[{i // 10:02d}:{(i * 6) % 60:02d}:00] {segment["text"]}\n'
                               for i, segment in enumerate(segments))
            assert "".join(successful_inputs) == expected, "Source text changed or tail was dropped"
            assert "PRIVATE_REASONING" not in note["markdown"]
            saved = request("/notes/" + note["id"], "PUT", {"title": note["title"], "markdown": note["markdown"] + "\n人工补充",
                                                          "category": note["category"], "revision": note["revision"]})
            assert saved["revision"] == 2 and saved["markdown"].endswith("人工补充")
            artifacts = request(path + "/artifacts")
            assert sum(item["kind"] == "SUMMARY_PART" for item in artifacts) == len(part_notes)
            assert any('"length"' in item["content"] for item in artifacts if item["kind"] == "LLM_RESPONSE")
            assert done["transcript"]["text"] == source and counters["asr"] == 1
            if args.chat_ui:
                ui_output = ROOT / f"artifacts/runtime/ai-chat-ui-{version}.json"
                ui_config = run / "ui-config.json"
                ui_config.write_text(json.dumps({"base": base, "userData": str(run / "electron"), "output": str(ui_output),
                                                "root": str(ROOT), "version": version, "customPrompt": custom_prompt,
                                                "recordingId": recording["id"]}, ensure_ascii=False), encoding="utf-8")
                probe = subprocess.run([str(ROOT / "desktop/node_modules/electron/dist/electron.exe"),
                                        str(ROOT / "scripts/ai-chat-ui-smoke.cjs"), str(ui_config)],
                                       timeout=180, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
                (run / "ui.log").write_bytes(probe.stdout)
                assert probe.returncode == 0, (run / "ui.log").read_text(encoding="utf-8", errors="replace")[-2000:]
                ui_result = json.loads(ui_output.read_text(encoding="utf-8"))
                assert ui_result["ok"] and chat_question_history
            report = {"ok": True, "version": version, "schemaVersion": health["schemaVersion"],
                      "syntheticModels": True, "sourceCharacters": len(source), "parts": len(part_notes),
                      "markdownCharacters": len(note["markdown"]), "asrCalls": counters["asr"],
                      "llmCalls": counters["llm"], "allPartsPreserved": True, "sourceAndTailPreserved": True,
                      "longNoteEditSaved": True, "retryWithoutRetranscription": True,
                      "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "testDirectory": str(run)}
            if args.chat_ui:
                report["chatUiVerified"] = True
                report["chatModelCalls"] = counters["chat"]
            destination = ROOT / f"artifacts/runtime/long-summary-{version}.json"
            destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False))
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            model.shutdown()
            model.server_close()


if __name__ == "__main__":
    main()
