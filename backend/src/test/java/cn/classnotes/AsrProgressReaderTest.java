package cn.classnotes;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsrProgressReaderTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper();
    String snapshot(String phase,long processed,long duration,int completed,int total) throws Exception {
        String id=UUID.randomUUID().toString();Path output=data.resolve("asr").resolve(id);Files.createDirectories(output);
        Files.writeString(output.resolve("progress.json"),json.writeValueAsString(Map.of("phase",phase,"processedMs",processed,"durationMs",duration,"completedChunks",completed,"totalChunks",total)));
        return id;
    }
    @Test void reportsActualAudioCoverageAndCommittedCompletion() throws Exception {
        var reader=new AsrProgressReader(data,json);
        String id=snapshot("transcribing",54000,67392,2,3);
        var progress=reader.read("RUNNING",id,null);
        assertThat(progress.percent()).isEqualTo(80);assertThat(progress.completedChunks()).isEqualTo(2);
        assertThat(progress.processedMs()).isEqualTo(54000);
        id=snapshot("completed",67392,67392,3,3);
        assertThat(reader.read("RUNNING",id,null).percent()).isEqualTo(99);
        assertThat(reader.read("RUNNING",id,null).phase()).isEqualTo("finalizing");
        assertThat(reader.read("SUCCEEDED",null,67392L).percent()).isEqualTo(100);
    }
    @Test void preparationAndUnsupportedWorkersDoNotInventPercentages() throws Exception {
        var reader=new AsrProgressReader(data,json);
        assertThat(reader.read("RUNNING",snapshot("loading_model",0,50000,0,2),null).percent()).isNull();
        assertThat(reader.read("RUNNING",UUID.randomUUID().toString(),null).percent()).isNull();
        String id=snapshot("failed",28000,60000,1,3);
        assertThat(reader.read("FAILED",id,null).percent()).isEqualTo(46);
        assertThat(reader.read("QUEUED",id,null).percent()).isNull();
    }
    @Test void brokenSnapshotsCannotBreakRecordingQueries() throws Exception {
        var reader=new AsrProgressReader(data,json);String id=snapshot("transcribing",1,10,1,2);
        Files.writeString(data.resolve("asr").resolve(id).resolve("progress.json"),"{broken");
        assertThat(reader.read("RUNNING",id,null).phase()).isEqualTo("starting");
        assertThat(reader.read("RUNNING","../../outside",null).percent()).isNull();
    }
}
