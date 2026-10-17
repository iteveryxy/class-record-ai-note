package cn.classnotes;

import static cn.classnotes.Models.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Store {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Path data;
    private final AsrProgressReader progress;
    public Store(JdbcTemplate db, ObjectMapper json, Path dataDirectory) {
        this.db = db; this.json = json; this.data = dataDirectory;
        this.progress = new AsrProgressReader(dataDirectory,json);
    }
    @PostConstruct
    void init() {
        // Leave evidence of interrupted attempts; never pretend an external operation completed.
        for (Job job : db.query("SELECT * FROM jobs WHERE status='RUNNING'", JOB)) {
            fail(job, "后端重启，任务已中断；可重试此阶段。原始结果仍保留。");
        }
    }
    static String now() { return Instant.now().toString(); }
    static String id() { return UUID.randomUUID().toString(); }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static final String RECORD_SELECT = """
            SELECT r.*, n.id AS note_id, n.title AS note_title, substr(n.markdown,1,220) AS preview,
            CASE WHEN n.confirmed_revision=n.revision THEN 1 ELSE 0 END AS confirmed,
            CASE WHEN r.transcript_status IN ('RUNNING','FAILED') THEN
              (SELECT j.id FROM jobs j WHERE j.recording_id=r.id AND j.stage='TRANSCRIBE'
               ORDER BY j.created_at DESC,j.rowid DESC LIMIT 1) END AS progress_job_id
            FROM recordings r LEFT JOIN notes n ON n.recording_id=r.id
            """;
    private static Long nullableLong(ResultSet r, String key) throws SQLException {
        long value = r.getLong(key); return r.wasNull() ? null : value;
    }
    private final RowMapper<Recording> RECORD = this::mapRecording;
    private Recording mapRecording(ResultSet r, int i) throws SQLException { return new Recording(r.getString("id"),
            r.getString("title"), r.getString("course"), r.getString("recorded_on"), r.getString("category"),
            r.getString("filename"), r.getLong("size"), nullableLong(r,"duration_ms"), r.getString("transcript_status"),
            r.getString("summary_status"), r.getString("sync_status"), r.getString("last_error"),
            r.getString("created_at"), r.getString("updated_at"), r.getString("note_id"), r.getString("note_title"),
            r.getString("preview"), r.getBoolean("confirmed"),
            progress.read(r.getString("transcript_status"),r.getString("progress_job_id"),nullableLong(r,"duration_ms"))); }
    private static final RowMapper<Note> NOTE = (r, i) -> new Note(r.getString("id"), r.getString("recording_id"),
            r.getString("title"), r.getString("markdown"), r.getString("category"), r.getLong("revision"),
            nullableLong(r,"confirmed_revision"), nullableLong(r,"synced_revision"), r.getString("notion_page_id"),
            r.getString("notion_url"), r.getString("notion_target"), r.getString("created_at"), r.getString("updated_at"));
    private static final RowMapper<Job> JOB = (r, i) -> new Job(r.getString("id"),r.getString("recording_id"),
            Stage.valueOf(r.getString("stage")),r.getString("status"),r.getInt("attempt"),r.getLong("note_revision"),
            r.getString("created_at"),r.getString("started_at"),r.getString("finished_at"),r.getString("error"));
    private final RowMapper<Transcript> TRANSCRIPT = this::mapTranscript;
    private Transcript mapTranscript(ResultSet r,int i) throws SQLException {
        try { return new Transcript(r.getString("id"),r.getString("recording_id"),r.getString("provider"),
                r.getLong("duration_ms"),r.getString("text"),json.readValue(r.getString("segments"), new TypeReference<>() {}),r.getString("created_at")); }
        catch (IOException e) { throw new SQLException("转录数据损坏", e); }
    }
    public Recording recording(String id) {
        return db.query(RECORD_SELECT + " WHERE r.id=?", RECORD, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "录音不存在"));
    }
    public List<Recording> recordings(String query, String course, String category, String date, boolean notesOnly) {
        StringBuilder sql = new StringBuilder(RECORD_SELECT + " WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (notesOnly) sql.append(" AND n.id IS NOT NULL");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (r.title LIKE ? ESCAPE '\\' OR r.course LIKE ? ESCAPE '\\' OR n.title LIKE ? ESCAPE '\\' OR n.markdown LIKE ? ESCAPE '\\')");
            String search = "%" + query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%";
            for (int i=0; i<4; i++) args.add(search);
        }
        if (course != null && !course.isBlank()) { sql.append(" AND r.course=?"); args.add(course); }
        if (category != null && !category.isBlank()) { sql.append(" AND r.category=?"); args.add(category); }
        if (date != null && !date.isBlank()) { sql.append(" AND r.recorded_on=?"); args.add(date); }
        sql.append(" ORDER BY r.recorded_on DESC,r.created_at DESC");
        return db.query(sql.toString(), RECORD, args.toArray());
    }
    @Transactional
    public Recording insertRecording(String id, String title, String course, String date, String category,
                                     String filename, String storedName, long size) {
        db.update("INSERT INTO recordings(id,title,course,recorded_on,category,filename,stored_name,size,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,title,course,date,category,filename,storedName,size,now(),now());
        log(id,null,"INFO","上传录音："+filename);
        enqueue(id, Stage.TRANSCRIBE);
        return recording(id);
    }
    public Path audioPath(String id) {
        recording(id);
        String name = db.queryForObject("SELECT stored_name FROM recordings WHERE id=?", String.class, id);
        return data.resolve("audio").resolve(name);
    }
    @Transactional
    public Recording moveCourse(String id, CourseMove move) {
        String target=move.course().trim();
        if(target.isBlank() || target.length()>120) throw new IllegalArgumentException("课程名称须为 1 至 120 字符");
        Recording old=recording(id);
        if(old.course().equals(target)) return old;
        if(!old.course().equals(move.expectedCourse().trim())) throw conflict("课程已被更改，请重新打开移动窗口后再试");
        int changed=db.update("""
                UPDATE recordings SET course=?,updated_at=?,
                sync_status=CASE WHEN sync_status='SUCCEEDED' THEN 'PENDING' ELSE sync_status END
                WHERE id=? AND course=? AND NOT EXISTS (
                    SELECT 1 FROM jobs WHERE recording_id=? AND status IN ('QUEUED','RUNNING'))
                """,target,now(),id,old.course(),id);
        if(changed!=1) throw conflict("此录音有任务排队或运行，请等待完成后再移动课程");
        log(id,null,"INFO","移动课程："+old.course()+" → "+target);
        return recording(id);
    }
    public Note noteForRecording(String id) {
        return db.query("SELECT * FROM notes WHERE recording_id=?", NOTE, id).stream().findFirst().orElse(null);
    }
    public Note note(String id) {
        return db.query("SELECT * FROM notes WHERE id=?", NOTE, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在"));
    }
    public Transcript currentTranscript(String id) {
        return db.query("SELECT t.* FROM transcripts t JOIN recordings r ON t.id=r.transcript_id WHERE r.id=?", TRANSCRIPT, id)
                .stream().findFirst().orElse(null);
    }
    public List<Job> jobs(String id) { return db.query("SELECT * FROM jobs WHERE recording_id=? ORDER BY created_at DESC,rowid DESC", JOB, id); }
    public List<LogEntry> logs(String id) {
        return db.query("SELECT * FROM logs WHERE recording_id=? ORDER BY id DESC LIMIT 500", (r,i) -> new LogEntry(r.getLong("id"),r.getString("job_id"),r.getString("level"),r.getString("message"),r.getString("created_at")), id);
    }
    public List<Version> versions(String noteId) {
        return db.query("SELECT * FROM note_versions WHERE note_id=? ORDER BY revision DESC", (r,i) -> new Version(r.getLong("id"),r.getLong("revision"),r.getString("title"),r.getString("markdown"),r.getString("category"),r.getString("source"),r.getString("created_at")), noteId);
    }
    public Detail detail(String id) {
        Note note = noteForRecording(id);
        return new Detail(recording(id),note,currentTranscript(id),jobs(id),logs(id),note == null ? List.of() : versions(note.id()),
                db.query("SELECT * FROM transcripts WHERE recording_id=? ORDER BY created_at DESC,rowid DESC", TRANSCRIPT,id));
    }
    @Transactional
    public Job enqueue(String recordingId, Stage stage) {
        Job job=enqueueCore(recordingId,stage);
        if(stage==Stage.REFINE) {
            Note note=noteForRecording(recordingId);
            List<AiTurn> history=aiTurns(note.id());
            if(history.isEmpty()) throw conflict("请先在 AI 对话框输入修改要求");
            addAiTurn(job,note.id(),history.getLast().message());
        }
        return job;
    }
    private Job enqueueCore(String recordingId, Stage stage) {
        Recording recording = recording(recordingId);
        if (db.queryForObject("SELECT count(*) FROM jobs WHERE recording_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,recordingId)>0)
            throw conflict("此录音已有任务排队或运行，请等待完成");
        Note note = noteForRecording(recordingId);
        if (stage == Stage.SUMMARIZE && currentTranscript(recordingId) == null) throw conflict("请先完成转录");
        if (stage == Stage.REFINE && note == null) throw conflict("请先生成课堂笔记");
        if (stage == Stage.SYNC && (note == null || !note.confirmed())) throw conflict("请先确认当前版本笔记");
        int attempt = db.queryForObject("SELECT count(*)+1 FROM jobs WHERE recording_id=? AND stage=?",Integer.class,recordingId,stage.name());
        Job job = new Job(id(),recordingId,stage,"QUEUED",attempt,note==null?0:note.revision(),now(),null,null,null);
        db.update("INSERT INTO jobs(id,recording_id,stage,status,attempt,note_revision,created_at) VALUES(?,?,?,?,?,?,?)",
                job.id(),recordingId,stage.name(),job.status(),attempt,job.noteRevision(),job.createdAt());
        status(recordingId,stage,"QUEUED",null);
        log(recordingId,job.id(),"INFO",stage+" 已加入队列（第 "+attempt+" 次）");
        return job;
    }
    @Transactional
    public Job claim() {
        List<Job> queue = db.query("SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created_at,rowid LIMIT 1", JOB);
        if (queue.isEmpty()) return null;
        Job job = queue.getFirst();
        if (db.update("UPDATE jobs SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'",now(),job.id())!=1) return null;
        status(job.recordingId(),job.stage(),"RUNNING",null);
        log(job.recordingId(),job.id(),"INFO",job.stage()+" 开始处理");
        return job;
    }
    private void status(String recordingId, Stage stage, String status, String error) {
        String column = switch(stage) { case TRANSCRIBE -> "transcript_status"; case SUMMARIZE, REFINE -> "summary_status"; case SYNC -> "sync_status"; };
        db.update("UPDATE recordings SET "+column+"=?,last_error=?,updated_at=? WHERE id=?",status,error,now(),recordingId);
    }
    public void log(String recordingId, String jobId, String level, String message) {
        db.update("INSERT INTO logs(recording_id,job_id,level,message,created_at) VALUES(?,?,?,?,?)",recordingId,jobId,level,message,now());
    }
    public void artifact(Job job, String kind, String raw) {
        db.update("INSERT INTO artifacts(recording_id,job_id,kind,content,created_at) VALUES(?,?,?,?,?)",job.recordingId(),job.id(),kind,raw,now());
    }
    public List<Artifact> artifacts(String recordingId) {
        recording(recordingId);
        return db.query("SELECT * FROM artifacts WHERE recording_id=? ORDER BY id DESC", (r,i)->new Artifact(r.getLong("id"),r.getString("job_id"),r.getString("kind"),r.getString("content"),r.getString("created_at")),recordingId);
    }
    @Transactional
    public void fail(Job job, String error) {
        db.update("UPDATE jobs SET status='FAILED',finished_at=?,error=? WHERE id=?",now(),error,job.id());
        status(job.recordingId(),job.stage(),"FAILED",error);
        log(job.recordingId(),job.id(),"ERROR",error);
    }
    private void success(Job job) {
        db.update("UPDATE jobs SET status='SUCCEEDED',finished_at=? WHERE id=?",now(),job.id());
        status(job.recordingId(),job.stage(),"SUCCEEDED",null);
        log(job.recordingId(),job.id(),"INFO",job.stage()+" 处理完成");
    }
    @Transactional
    public void completeTranscript(Job job, AsrResult result, boolean autoSummarize) throws IOException {
        if (result.text()==null || result.text().isBlank() || result.durationMs()<=0 || result.segments()==null || result.segments().isEmpty())
            throw conflict("转录未识别出有效文本，请检查音频后重试");
        for (Segment segment : result.segments()) {
            if (segment.startMs()<0 || segment.endMs()<segment.startMs() || segment.endMs()>result.durationMs()+1000 || segment.text()==null)
                throw conflict("ASR 返回了无效时间戳");
        }
        String transcriptId = id();
        db.update("INSERT INTO transcripts(id,recording_id,job_id,provider,duration_ms,text,segments,created_at) VALUES(?,?,?,?,?,?,?,?)",
                transcriptId,job.recordingId(),job.id(),result.provider(),result.durationMs(),result.text(),json.writeValueAsString(result.segments()),now());
        db.update("UPDATE recordings SET transcript_id=?,duration_ms=? WHERE id=?",transcriptId,result.durationMs(),job.recordingId());
        if(noteForRecording(job.recordingId())!=null) {
            db.update("UPDATE notes SET confirmed_revision=NULL,updated_at=? WHERE recording_id=?",now(),job.recordingId());
            db.update("UPDATE recordings SET summary_status='PENDING',sync_status='PENDING' WHERE id=?",job.recordingId());
            log(job.recordingId(),job.id(),"WARN","转录已有新版本；原笔记保留，请重新整理或核对后确认");
        }
        success(job);
        if (autoSummarize) enqueue(job.recordingId(),Stage.SUMMARIZE);
    }
    @Transactional
    public Note completeSummary(Job job, String markdown) {
        Note old = noteForRecording(job.recordingId());
        if ((old==null?0:old.revision()) != job.noteRevision()) throw conflict("整理期间笔记已被编辑，生成结果保留在原始结果中，请重试或手动合并");
        Recording rec = recording(job.recordingId());
        String noteId = old==null?id():old.id();
        long revision = old==null?1:old.revision()+1;
        if (old==null) db.update("INSERT INTO notes(id,recording_id,title,markdown,category,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                noteId,rec.id(),rec.title(),markdown,rec.category(),revision,now(),now());
        else db.update("UPDATE notes SET markdown=?,revision=?,confirmed_revision=NULL,updated_at=? WHERE id=?",markdown,revision,now(),noteId);
        addVersion(note(noteId),"AI");
        db.update("UPDATE recordings SET sync_status='PENDING' WHERE id=?",rec.id());
        success(job);
        return note(noteId);
    }
    private static final String AI_TURN_SELECT="""
            SELECT t.*,j.status,j.error,j.note_revision AS base_revision FROM note_ai_turns t
            JOIN jobs j ON j.id=t.job_id
            """;
    private static final RowMapper<AiTurn> AI_TURN=(r,i)->new AiTurn(r.getString("id"),r.getString("job_id"),
            r.getString("user_message"),r.getString("assistant_message"),r.getLong("base_revision"),
            nullableLong(r,"result_revision"),r.getBoolean("changed"),r.getString("status"),r.getString("error"),r.getString("created_at"));
    public List<AiTurn> aiTurns(String noteId) {
        note(noteId);
        List<AiTurn> turns=new ArrayList<>(db.query(AI_TURN_SELECT+" WHERE t.note_id=? ORDER BY t.created_at DESC,t.rowid DESC LIMIT 40",AI_TURN,noteId));
        java.util.Collections.reverse(turns);
        return turns;
    }
    public AiTurn aiTurnForJob(String jobId) {
        return db.query(AI_TURN_SELECT+" WHERE t.job_id=?",AI_TURN,jobId).stream().findFirst()
                .orElseThrow(()->conflict("AI 修改要求不存在，请重新发送"));
    }
    private void addAiTurn(Job job,String noteId,String message) {
        db.update("INSERT INTO note_ai_turns(id,note_id,job_id,user_message,created_at) VALUES(?,?,?,?,?)",id(),noteId,job.id(),message,now());
    }
    @Transactional
    public AiTurn enqueueAiChat(String noteId,AiChatRequest input) {
        Note note=note(noteId);
        if(note.revision()!=input.revision()) throw conflict("笔记版本已改变，请刷新后重新发送；修改要求仍保留在输入框");
        if(input.message()==null||input.message().isBlank()||input.message().length()>5000) throw new IllegalArgumentException("修改要求须为 1–5000 字符");
        Job job=enqueueCore(note.recordingId(),Stage.REFINE);
        addAiTurn(job,noteId,input.message().trim());
        return aiTurnForJob(job.id());
    }
    @Transactional
    public Note completeRefinement(Job job,Refinement result) {
        Note old=noteForRecording(job.recordingId());
        if(old==null||old.revision()!=job.noteRevision()) throw conflict("AI 对话期间笔记已被编辑，生成结果保留在原始结果中，请基于新版本重试");
        if(result.markdown()==null||result.markdown().isBlank()||result.markdown().length()>MAX_NOTE_CHARS) throw conflict("AI 修改结果为空或超过单篇长度上限，原始结果已保留");
        boolean changed=!old.markdown().equals(result.markdown());
        if(changed) {
            db.update("UPDATE notes SET markdown=?,revision=revision+1,confirmed_revision=NULL,updated_at=? WHERE id=? AND revision=?",result.markdown(),now(),old.id(),old.revision());
            addVersion(note(old.id()),"AI_CHAT");
            db.update("UPDATE recordings SET sync_status='PENDING' WHERE id=?",job.recordingId());
        }
        Note updated=note(old.id());
        db.update("UPDATE note_ai_turns SET assistant_message=?,result_revision=?,changed=? WHERE job_id=?",result.reply(),updated.revision(),changed,job.id());
        success(job);
        return updated;
    }
    private void addVersion(Note note, String source) {
        db.update("INSERT INTO note_versions(note_id,revision,title,markdown,category,source,created_at) VALUES(?,?,?,?,?,?,?)",
                note.id(),note.revision(),note.title(),note.markdown(),note.category(),source,now());
    }
    @Transactional
    public Note editNote(String id, NoteEdit edit) {
        Note old = note(id);
        if (old.revision()!=edit.revision()) throw conflict("笔记已有新版本，请重新加载后合并；编辑内容仍在页面中");
        if (db.queryForObject("SELECT count(*) FROM jobs WHERE recording_id=? AND stage='SYNC' AND status IN ('QUEUED','RUNNING')",Integer.class,old.recordingId())>0)
            throw conflict("Notion 同步中，请等待完成再保存");
        db.update("UPDATE notes SET title=?,markdown=?,category=?,revision=revision+1,confirmed_revision=NULL,updated_at=? WHERE id=? AND revision=?",
                edit.title().trim(),edit.markdown(),edit.category().trim(),now(),id,edit.revision());
        db.update("UPDATE recordings SET title=?,category=?,sync_status='PENDING',updated_at=? WHERE id=?",edit.title().trim(),edit.category().trim(),now(),old.recordingId());
        Note updated=note(id); addVersion(updated,"MANUAL");
        log(old.recordingId(),null,"INFO","已保存笔记版本 v"+updated.revision()+"，需要重新确认后同步");
        return updated;
    }
    @Transactional
    public Note confirm(String id, long revision) {
        Note note=note(id);
        if (note.revision()!=revision) throw conflict("版本已改变，请检查并确认当前版本");
        db.update("UPDATE notes SET confirmed_revision=revision,updated_at=? WHERE id=?",now(),id);
        log(note.recordingId(),null,"INFO","已确认笔记 v"+revision);
        return note(id);
    }
    public void bindNotion(Note note, String pageId, String url, String target) {
        // Persist immediately after page creation, before writing its content.
        db.update("UPDATE notes SET notion_page_id=?,notion_url=?,notion_target=?,notion_pending_target=NULL WHERE id=?",pageId,url,target,note.id());
    }
    public boolean pendingNotionCreation(String noteId, String target) {
        return db.queryForObject("SELECT count(*) FROM notes WHERE id=? AND notion_pending_target=?",Integer.class,noteId,target)>0;
    }
    public void markNotionCreation(String noteId, String target) {
        db.update("UPDATE notes SET notion_pending_target=? WHERE id=?",target,noteId);
    }
    public void resetNotionCreation(String noteId, long revision) {
        Note note=note(noteId);
        if(note.revision()!=revision) throw conflict("笔记版本已改变");
        if(jobs(note.recordingId()).stream().anyMatch(j->j.status().equals("RUNNING")||j.status().equals("QUEUED"))) throw conflict("任务运行中，请等待完成");
        db.update("UPDATE notes SET notion_pending_target=NULL WHERE id=?",noteId);
        log(note.recordingId(),null,"WARN","用户核对 Notion 未创建后，重置了不确定的创建状态");
    }
    @Transactional
    public void completeSync(Job job, Note snapshot) {
        Note current=note(snapshot.id());
        if (current.revision()!=snapshot.revision() || !current.confirmed()) throw conflict("同步版本已失效，请重新确认");
        db.update("UPDATE notes SET synced_revision=?,updated_at=? WHERE id=?",snapshot.revision(),now(),snapshot.id());
        success(job);
    }
}
