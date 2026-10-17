package cn.classnotes;

import static cn.classnotes.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.demo",havingValue="false",matchIfMissing=true)
public class PythonAsrProvider implements AsrProvider {
    private final JsonHttp http; private final Settings settings; private final Store store; private final ObjectMapper json;
    public PythonAsrProvider(JsonHttp http, Settings settings, Store store, ObjectMapper json) { this.http=http;this.settings=settings;this.store=store;this.json=json; }
    public AsrResult transcribe(Job job, Path audio) throws Exception {
        Map<String,String> headers=new HashMap<>();
        if(!settings.get("workerToken").isBlank()) headers.put("Authorization","Bearer "+settings.get("workerToken"));
        var response=http.send("POST",settings.get("workerUrl").replaceAll("/+$","")+"/transcribe",headers,
                Map.of("inputPath",audio.toAbsolutePath().toString(),"jobId",job.id()),Duration.ofHours(6));
        store.artifact(job,"ASR_RESPONSE",settings.redact(response.body()));
        JsonHttp.requireSuccess(response,"ASR Worker");
        return json.treeToValue(response.json(),AsrResult.class);
    }
}
