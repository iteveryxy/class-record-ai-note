package cn.classnotes;

import static cn.classnotes.Models.*;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Store store; private final Settings settings; private final Path data; private final AudioStorage audioStorage;
    @Value("${app.version:0.2.6}") private String version;
    public ApiController(Store store, Settings settings, Path dataDirectory, AudioStorage audioStorage) { this.store=store;this.settings=settings;this.data=dataDirectory;this.audioStorage=audioStorage; }
    @GetMapping("/health")
    public Map<String,Object> health() { return Map.of("status","ok","demo",settings.demo(),"version",version,"schemaVersion",DatabaseMigrations.CURRENT_VERSION); }
    @GetMapping("/activity") public Map<String,Object> activity() {
        var recordings=store.recordings(null,null,null,null,false);
        return Map.of("active",recordings.stream().anyMatch(r->Set.of("QUEUED","RUNNING").contains(r.transcriptStatus())||Set.of("QUEUED","RUNNING").contains(r.summaryStatus())||Set.of("QUEUED","RUNNING").contains(r.syncStatus())));
    }
    @GetMapping("/settings") public Map<String,Object> settings() { return settings.publicSettings(); }
    @PutMapping("/settings") public Map<String,Object> settings(@RequestBody Map<String,String> input) { settings.save(input); return settings.publicSettings(); }
    @PostMapping("/settings/system-prompt/reset") public Map<String,Object> resetSystemPrompt() { return settings.resetSystemPrompt(); }
    @GetMapping("/recordings")
    public List<Recording> recordings(@RequestParam(required=false) String q,@RequestParam(required=false) String course,
                                     @RequestParam(required=false) String category,@RequestParam(required=false) String date,
                                     @RequestParam(defaultValue="false") boolean notesOnly) {
        return store.recordings(q,course,category,date,notesOnly);
    }
    @PostMapping("/recordings")
    public ResponseEntity<Recording> upload(@RequestParam MultipartFile file,@RequestParam String title,
                                           @RequestParam String course,@RequestParam String recordedOn,
                                           @RequestParam(defaultValue="课堂笔记") String category) throws IOException {
        title=checked(title,200,"标题"); course=checked(course,120,"课程"); category=checked(category,80,"分类");
        LocalDate.parse(recordedOn);
        String filename=file.getOriginalFilename();
        if(filename==null||filename.isBlank()) throw new IllegalArgumentException("文件名为空");
        filename=Path.of(filename.replace('\\','/')).getFileName().toString();
        if(filename.length()>255) throw new IllegalArgumentException("文件名过长");
        String ext=filename.contains(".")?filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!Set.of("wav","m4a").contains(ext)||file.isEmpty()) throw new IllegalArgumentException("请选择非空 M4A 或 WAV 文件");
        byte[] header;
        try(InputStream stream=file.getInputStream()) { header=stream.readNBytes(12); }
        String marker=header.length>=12?new String(header,java.nio.charset.StandardCharsets.ISO_8859_1):"";
        if(!(ext.equals("wav")&&(marker.startsWith("RIFF")||marker.startsWith("RF64"))&&marker.endsWith("WAVE"))&&!(ext.equals("m4a")&&marker.substring(Math.min(4,marker.length())).startsWith("ftyp")))
            throw new IllegalArgumentException("文件头与音频格式不匹配，FFmpeg 支持的 M4A/WAV 才能转录");
        String id=Store.id(),stored=id+"."+ext;
        Path target=data.resolve("audio").resolve(stored);
        try {
            file.transferTo(target);
            Recording rec=store.insertRecording(id,title,course,recordedOn,category,filename,stored,file.getSize());
            return ResponseEntity.status(201).body(rec);
        } catch(Exception e) { Files.deleteIfExists(target); throw e; }
    }
    private static String checked(String value,int limit,String name) {
        if(value==null||value.isBlank()||value.trim().length()>limit) throw new IllegalArgumentException(name+"不能为空且不能超过 "+limit+" 字符");
        return value.trim();
    }
    @GetMapping("/recordings/{id}") public Detail detail(@PathVariable String id) { return store.detail(id); }
    @PutMapping("/recordings/{id}/course") public Recording moveCourse(@PathVariable String id,@Valid @RequestBody CourseMove move) {
        return store.moveCourse(id,move);
    }
    @GetMapping("/recordings/{id}/artifacts") public List<Artifact> artifacts(@PathVariable String id) { return store.artifacts(id); }
    @GetMapping("/recordings/{id}/audio") public ResponseEntity<Resource> audio(@PathVariable String id) {
        Recording rec=store.recording(id);
        if(!rec.audioAvailable())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"本地录音已清理，转录文字和笔记仍然保留");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(rec.filename().toLowerCase(Locale.ROOT).endsWith(".wav")?"audio/wav":"audio/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline").body(new FileSystemResource(store.audioPath(id)));
    }
    @GetMapping("/recordings/{id}/audio-storage") public AudioStorageInfo audioStorage(@PathVariable String id) throws IOException { return audioStorage.inspect(id); }
    @PostMapping("/recordings/{id}/audio/delete") public AudioStorageInfo deleteAudio(@PathVariable String id,@Valid @RequestBody AudioDeleteRequest input) throws IOException { return audioStorage.deleteConfirmed(id,input); }
    @PostMapping("/recordings/{id}/jobs") public ResponseEntity<Job> action(@PathVariable String id,@Valid @RequestBody ActionRequest action) {
        if(action.stage()==null) throw new IllegalArgumentException("请指定处理阶段");
        if(action.stage()==Stage.SYNC&&settings.demo()) throw new IllegalArgumentException("演示模式不写入 Notion；请以正式模式启动");
        return ResponseEntity.accepted().body(store.enqueue(id,action.stage()));
    }
    @PutMapping("/notes/{id}") public Note edit(@PathVariable String id,@Valid @RequestBody NoteEdit edit) { return store.editNote(id,edit); }
    @GetMapping("/notes/{id}/chat") public List<AiTurn> aiChat(@PathVariable String id) { return store.aiTurns(id); }
    @PostMapping("/notes/{id}/chat") public ResponseEntity<AiTurn> aiChat(@PathVariable String id,@Valid @RequestBody AiChatRequest input) {
        return ResponseEntity.accepted().body(store.enqueueAiChat(id,input));
    }
    @PostMapping("/notes/{id}/confirm") public Note confirm(@PathVariable String id,@RequestBody RevisionRequest input) { return store.confirm(id,input.revision()); }
    @PostMapping("/notes/{id}/reset-notion-creation") public Map<String,String> reset(@PathVariable String id,@RequestBody RevisionRequest input) { store.resetNotionCreation(id,input.revision()); return Map.of("status","ok"); }
    @GetMapping("/notes/{id}/markdown") public ResponseEntity<String> markdown(@PathVariable String id) {
        Note note=store.note(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"class-note-"+note.id()+".md\"").body(note.markdown());
    }
}
