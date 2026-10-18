package cn.classnotes;

import static cn.classnotes.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Component
@ConditionalOnProperty(name="app.queue-enabled",havingValue="true",matchIfMissing=true)
public class JobRunner {
    private static final Logger log=LoggerFactory.getLogger(JobRunner.class);
    private final Store store; private final Settings settings; private final AsrProvider asr;
    private final KnowledgeExtractor extractor; private final NotionSync notion;
    private final NoteRefiner refiner;
    private final AudioStorage audioStorage;
    private volatile boolean ready;
    public JobRunner(Store store, Settings settings, AsrProvider asr, KnowledgeExtractor extractor, NotionSync notion,NoteRefiner refiner,AudioStorage audioStorage) {
        this.store=store;this.settings=settings;this.asr=asr;this.extractor=extractor;this.notion=notion;this.refiner=refiner;this.audioStorage=audioStorage;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { ready=true; }
    @Scheduled(fixedDelay=1000,initialDelay=1500)
    public synchronized void tick() {
        if(!ready) return;
        Job job=store.claim();
        if(job==null) return;
        try {
            switch(job.stage()) {
                case TRANSCRIBE -> {
                    AsrResult result=asr.transcribe(job,store.audioPath(job.recordingId()));
                    store.completeTranscript(job,result,settings.autoSummarize()&&settings.llmReady());
                    try { audioStorage.afterTranscription(job.recordingId()); }
                    catch(Exception cleanup) { store.log(job.recordingId(),job.id(),"WARN","转录已保存，但音频清理未完成；可稍后手动清理");log.warn("Audio cleanup failed for {}",job.id(),cleanup); }
                    if(settings.autoSummarize()&&!settings.llmReady()) store.log(job.recordingId(),job.id(),"WARN","转录完成；配置大模型后可手动整理笔记");
                }
                case SUMMARIZE -> store.completeSummary(job,extractor.summarize(job,store.recording(job.recordingId()),store.currentTranscript(job.recordingId())));
                case REFINE -> store.completeRefinement(job,refiner.refine(job));
                case SYNC -> notion.sync(job);
            }
        } catch(Exception e) {
            String message=settings.redact(e.getMessage());
            store.fail(job,message.length()>2000?message.substring(0,2000):message);
            log.warn("Job {} ({}) failed: {}",job.id(),job.stage(),message);
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
}
