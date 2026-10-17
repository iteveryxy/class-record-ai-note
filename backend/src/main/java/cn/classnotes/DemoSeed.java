package cn.classnotes;

import static cn.classnotes.Models.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.demo",havingValue="true")
public class DemoSeed implements ApplicationRunner {
    private final Store store; private final DemoProvider demo; private final Path data;
    public DemoSeed(Store store,DemoProvider demo,Path dataDirectory) { this.store=store;this.demo=demo;this.data=dataDirectory; }
    public void run(ApplicationArguments args) throws Exception {
        if(!store.recordings(null,null,null,null,false).isEmpty()) return;
        seed("导数的定义与可导性","高等数学","2026-10-13","课堂笔记",true);
        seed("分段函数 · 连接点分析","高等数学","2026-10-12","重点复习",false);
    }
    private void seed(String title,String course,String date,String category,boolean confirmed) throws Exception {
        String id=Store.id(),filename=id+".wav";
        byte[] samples=new byte[16000*2*36];
        ByteBuffer wav=ByteBuffer.allocate(44+samples.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36+samples.length).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(samples.length).put(samples);
        Files.write(data.resolve("audio").resolve(filename),wav.array());
        store.insertRecording(id,title,course,date,category,"演示音频（静音示例）.wav",filename,wav.capacity());
        Job transcribe=store.claim();
        store.completeTranscript(transcribe,demo.transcribe(transcribe,store.audioPath(id)),false);
        store.enqueue(id,Stage.SUMMARIZE);
        Job summary=store.claim();
        Note note=store.completeSummary(summary,demo.complete(summary,"",""));
        if(confirmed) store.confirm(note.id(),note.revision());
    }
}
