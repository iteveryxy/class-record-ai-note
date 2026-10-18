package cn.classnotes;

import static cn.classnotes.Models.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only audio bytes are disposable. Text, raw model output, timestamps and versions survive. */
@Service
public class AudioStorage {
    private final Store store; private final Settings settings; private final JdbcTemplate db; private final Path data;
    public AudioStorage(Store store, Settings settings, JdbcTemplate db, Path dataDirectory) {
        this.store=store;this.settings=settings;this.db=db;this.data=dataDirectory;
    }
    private boolean active(String id, boolean all) {
        return db.queryForObject("SELECT count(*) FROM jobs WHERE recording_id=? AND status IN ('QUEUED','RUNNING')"+(all?"":" AND stage='TRANSCRIBE'"),Integer.class,id)>0;
    }
    private boolean shared(String id) {
        return db.queryForObject("SELECT count(*) FROM recordings WHERE id<>? AND stored_name=(SELECT stored_name FROM recordings WHERE id=?)",Integer.class,id,id)>0;
    }
    private void rejectLink(Path file) throws IOException {
        if(Files.isSymbolicLink(file)||(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!file.toRealPath().equals(file.toAbsolutePath().normalize()))) throw Store.conflict("音频目录包含链接，已停止清理");
    }
    private List<Path> files(String id) throws IOException {
        List<Path> result=new ArrayList<>();
        Path original=store.audioPath(id);
        rejectLink(original.getParent());rejectLink(original);
        if(Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS))result.add(original);
        Path asr=data.resolve("asr");rejectLink(asr);
        for(String job:db.queryForList("SELECT id FROM jobs WHERE recording_id=? AND stage='TRANSCRIBE'",String.class,id)) {
            if(!UUID.fromString(job).toString().equals(job))throw Store.conflict("转录缓存路径无效");
            Path directory=asr.resolve(job);rejectLink(directory);
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))continue;
            try(var paths=Files.walk(directory)) {
                for(Path file:paths.toList()) {
                    rejectLink(file);
                    String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&(name.endsWith(".wav")||name.endsWith(".m4a")))result.add(file);
                }
            }
        }
        return result;
    }
    @Transactional(readOnly=true)
    public AudioStorageInfo inspect(String id) throws IOException {
        Recording recording=store.recording(id);Transcript transcript=store.currentTranscript(id);
        List<Path> files=files(id);long bytes=0;for(Path file:files)bytes+=Files.size(file);
        String reason=transcript==null||!"SUCCEEDED".equals(recording.transcriptStatus())?"仅可清理已成功转录的录音，失败录音保留以便重试":active(id,true)?"录音仍有任务正在处理，请完成后再清理":recording.audioAvailable()&&shared(id)?"此录音与其他记录共享音频，暂不能单独清理":null;
        return new AudioStorageInfo(recording.audioAvailable(),files.size(),bytes,reason==null&&!files.isEmpty(),transcript==null?null:transcript.id(),reason);
    }
    @Transactional
    public AudioStorageInfo deleteConfirmed(String id, AudioDeleteRequest input) throws IOException {
        if(!input.confirmed())throw new IllegalArgumentException("请先确认清理本地音频");
        Transcript transcript=store.currentTranscript(id);
        if(transcript==null||!transcript.id().equals(input.transcriptId()))throw Store.conflict("转录版本已变化，请重新确认清理范围");
        discard(id,true);
        return inspect(id);
    }
    @Transactional
    public void afterTranscription(String id) throws IOException {
        if(!Boolean.parseBoolean(settings.get("retainRecordings")))discard(id,false);
    }
    private void discard(String id, boolean manual) throws IOException {
        Recording recording=store.recording(id);
        if(!"SUCCEEDED".equals(recording.transcriptStatus())||store.currentTranscript(id)==null)
            throw Store.conflict("仅可清理已成功转录的录音，失败录音保留以便重试");
        if(active(id,manual))throw Store.conflict("录音仍有任务正在处理，请完成后再清理");
        if(recording.audioAvailable()&&shared(id))throw Store.conflict("此录音与其他记录共享音频，暂不能单独清理");
        List<Path> files=files(id);long bytes=0;int removed=0;
        // The SQLite transaction also serializes enqueueing with deletion on the single connection.
        try {
            for(Path file:files) { long size=Files.size(file);if(Files.deleteIfExists(file)){removed++;bytes+=size;} }
        } finally {
            if(removed>0)store.log(id,null,"INFO","已清理本地音频："+removed+" 个文件，"+bytes+" 字节；转录、笔记与原始文字结果保留");
        }
    }
}
