package cn.classnotes;

import static cn.classnotes.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Progress is advisory; the committed SQLite job status decides completion. */
public class AsrProgressReader {
    private final Path data;
    private final ObjectMapper json;
    public AsrProgressReader(Path dataDirectory, ObjectMapper json) { this.data=dataDirectory;this.json=json; }
    public TranscriptionProgress read(String status, String jobId, Long savedDuration) {
        long saved=savedDuration==null?0:Math.max(0,savedDuration);
        if("SUCCEEDED".equals(status)) return new TranscriptionProgress("completed","转录完成",100,saved,saved,0,0);
        if("QUEUED".equals(status)||"UPLOADED".equals(status))
            return new TranscriptionProgress("queued","等待开始转录",null,0,0,0,0);
        JsonNode snapshot=null;
        try {
            if(jobId!=null && UUID.fromString(jobId).toString().equals(jobId)) {
                Path file=data.resolve("asr").resolve(jobId).resolve("progress.json");
                if(Files.isRegularFile(file)&&Files.size(file)<=16384) snapshot=json.readTree(Files.readAllBytes(file));
            }
        } catch(Exception ignored) { /* Missing or damaged progress must not break recordings or retries. */ }
        long duration=snapshot==null?0:Math.max(0,snapshot.path("durationMs").asLong(0));
        long processed=snapshot==null?0:Math.max(0,Math.min(duration,snapshot.path("processedMs").asLong(0)));
        int total=snapshot==null?0:Math.max(0,snapshot.path("totalChunks").asInt(0));
        int completed=snapshot==null?0:Math.max(0,Math.min(total,snapshot.path("completedChunks").asInt(0)));
        String phase=snapshot==null?"starting":snapshot.path("phase").asText("starting");
        boolean failed="FAILED".equals(status);
        if(failed) phase="failed";
        else if(Set.of("completed","failed").contains(phase)) phase="finalizing";
        Integer percent=duration>0 && Set.of("transcribing","finalizing","failed").contains(phase)
                ?(int)Math.min(99,processed*100.0/duration):null;
        String message=switch(phase) {
            case "converting" -> "正在转换音频";
            case "loading_model" -> "正在准备语音模型，首次使用可能需要下载";
            case "transcribing" -> "正在识别课堂录音";
            case "finalizing" -> "正在保存转录结果";
            case "failed" -> "转录未完成，可在处理记录中重试";
            default -> "正在启动本地转录";
        };
        return new TranscriptionProgress(phase,message,percent,processed,duration,completed,total);
    }
}
