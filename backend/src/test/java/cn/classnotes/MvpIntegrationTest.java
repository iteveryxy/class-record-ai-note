package cn.classnotes;

import static cn.classnotes.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.demo=false","app.queue-enabled=false","app.llm-api-key=test-secret","app.notion-token=notion-test-secret","app.llm-model=test-model"})
@AutoConfigureMockMvc
class MvpIntegrationTest {
    static final String TARGET="11111111-1111-1111-1111-111111111111";
    static final String PAGE="22222222-2222-2222-2222-222222222222";
    static final HttpServer server;
    static final ObjectMapper mapper=new ObjectMapper();
    static final AtomicInteger creates=new AtomicInteger();
    static final List<String> llmInputs=new ArrayList<>();
    static final List<String> llmSystems=new ArrayList<>();
    static final List<JsonNode> notionProperties=new ArrayList<>();
    static volatile boolean pageExists=false, failMarkdown=false, uncertainCreate=false;
    static volatile String finish="stop", content="## 核心概念\n定义及其条件 [00:00:00]";
    static {
        try {
            server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if((path.equals("/pages")&&method.equals("POST"))||(path.equals("/pages/"+PAGE)&&method.equals("PATCH")))
                    notionProperties.add(mapper.readTree(request).path("properties"));
                Object body=Map.of("object","page","id",PAGE,"url","https://www.notion.so/"+PAGE,"parent",Map.of("data_source_id",TARGET));
                int status=200;
                if(path.endsWith("/chat/completions")) {
                    llmSystems.add(mapper.readTree(request).path("messages").path(0).path("content").asText());
                    llmInputs.add(mapper.readTree(request).path("messages").path(1).path("content").asText());
                    body=Map.of("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",content,"reasoning_content","私有推理，不得写入笔记"))));
                } else if(path.equals("/data_sources/"+TARGET)) {
                    body=Map.of("properties",Map.of("Name",Map.of("type","title"),"Source ID",Map.of("type","rich_text"),"Course",Map.of("type","rich_text"),"Date",Map.of("type","date"),"Category",Map.of("type","select")));
                } else if(path.endsWith("/query")) {
                    body=Map.of("results",pageExists?List.of(Map.of("id",PAGE,"url","https://www.notion.so/"+PAGE)):List.of(),"has_more",false);
                } else if(path.equals("/pages")&&method.equals("POST")) {
                    creates.incrementAndGet();
                    if(uncertainCreate) { status=500;body=Map.of("object","error"); } else pageExists=true;
                } else if(path.endsWith("/markdown")&&failMarkdown) { status=503;body=Map.of("object","error"); }
                byte[] response=mapper.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);exchange.close();
            });server.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) throws Exception {
        Path data=Path.of("target","mvp-tests-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(data);
        registry.add("app.data-dir",data::toString);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        registry.add("app.llm-base-url",()->base);registry.add("app.notion-base-url",()->base);
        registry.add("app.notion-data-source-id",()->TARGET);
    }
    @Autowired Store store; @Autowired Settings settings; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired KnowledgeExtractor extractor; @Autowired CompatibleLlmProvider llm; @Autowired NotionSync notion;
    @Autowired NoteRefiner refiner;
    @Autowired MockMvc mvc;
    @Autowired Path dataDirectory;
    @BeforeEach void reset() {
        for(String table:List.of("note_ai_turns","artifacts","logs","jobs","note_versions","notes","transcripts","recordings","settings")) db.update("DELETE FROM "+table);
        creates.set(0);pageExists=false;failMarkdown=false;uncertainCreate=false;finish="stop";content="## 核心概念\n定义及其条件 [00:00:00]";llmInputs.clear();llmSystems.clear();
        notionProperties.clear();
    }
    @AfterAll static void close() { server.stop(0); }
    static byte[] wav() {
        byte[] samples=new byte[32000];ByteBuffer b=ByteBuffer.allocate(44+samples.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(samples.length+36).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples.length).put(samples);return b.array();
    }
    Recording upload() throws Exception {
        var response=mvc.perform(multipart("/api/recordings").file(new MockMultipartFile("file","lecture.wav","audio/wav",wav()))
                .param("title","导数定义").param("course","高等数学").param("recordedOn","2026-10-13"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readValue(response,Recording.class);
    }
    AsrResult transcript(String text) { return new AsrResult("test-asr",1000,text,List.of(new Segment(0,1000,text,"window")),json.createObjectNode()); }
    Note prepareNote() throws Exception {
        Recording r=upload();Job first=store.claim();store.completeTranscript(first,transcript("可导一定连续，连续不一定可导"),false);
        store.enqueue(r.id(),Stage.SUMMARIZE);Job summary=store.claim();
        return store.completeSummary(summary,"## 核心概念\n可导与连续 [00:00:00]");
    }
    org.springframework.test.web.servlet.ResultActions move(String id,String course,String expectedCourse) throws Exception {
        return mvc.perform(put("/api/recordings/"+id+"/course").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("course",course,"expectedCourse",expectedCourse))));
    }
    @Test void movingCoursePreservesRecordingNotesHistoryAndUpdatesSameNotionPage() throws Exception {
        Note initial=prepareNote();
        Note edited=store.editNote(initial.id(),new NoteEdit(initial.title(),initial.markdown()+"\n$$f'(x)$$",initial.category(),initial.revision()));
        store.enqueueAiChat(edited.id(),new AiChatRequest("解释符号，保留笔记",edited.revision()));
        store.completeRefinement(store.claim(),new Refinement(edited.markdown(),"符号表示导数，笔记保留原样。"));
        store.confirm(edited.id(),edited.revision());store.enqueue(edited.recordingId(),Stage.SYNC);notion.sync(store.claim());
        Detail before=store.detail(edited.recordingId());var chat=store.aiTurns(edited.id());
        var artifacts=store.artifacts(edited.recordingId());byte[] audio=Files.readAllBytes(store.audioPath(edited.recordingId()));
        move(edited.recordingId(),"  线性代数  ","高等数学").andExpect(status().isOk()).andExpect(jsonPath("$.course").value("线性代数"))
                .andExpect(jsonPath("$.syncStatus").value("PENDING"));
        Detail after=store.detail(edited.recordingId());
        assertThat(after.note()).isEqualTo(before.note());assertThat(after.transcripts()).isEqualTo(before.transcripts());
        assertThat(after.versions()).isEqualTo(before.versions());assertThat(after.jobs()).isEqualTo(before.jobs());
        assertThat(store.aiTurns(edited.id())).isEqualTo(chat);assertThat(store.artifacts(edited.recordingId())).isEqualTo(artifacts);
        assertThat(Files.readAllBytes(store.audioPath(edited.recordingId()))).isEqualTo(audio);
        assertThat(after.recording()).usingRecursiveComparison().ignoringFields("course","syncStatus","updatedAt").isEqualTo(before.recording());
        assertThat(after.logs()).hasSize(before.logs().size()+1);assertThat(after.logs().getFirst().message()).contains("高等数学 → 线性代数");
        assertThat(store.recordings(null,"高等数学",null,null,false)).isEmpty();
        assertThat(store.recordings(null,"线性代数",null,null,true)).extracting(Recording::id).containsExactly(edited.recordingId());
        store.enqueue(edited.recordingId(),Stage.SYNC);notion.sync(store.claim());
        assertThat(creates.get()).isEqualTo(1);assertThat(store.note(edited.id()).notionPageId()).isEqualTo(PAGE);
        assertThat(notionProperties.getLast().path("Course").path("rich_text").path(0).path("text").path("content").asText()).isEqualTo("线性代数");
        assertThat(notionProperties.getLast().path("Source ID")).isEqualTo(notionProperties.getFirst().path("Source ID"));
        assertThat(store.recording(edited.recordingId()).syncStatus()).isEqualTo("SUCCEEDED");
    }
    @Test void courseMoveValidatesInputAndRejectsStaleCourseWithoutLosingData() throws Exception {
        Note note=prepareNote();Detail before=store.detail(note.recordingId());
        for(String invalid:List.of(" ","字".repeat(121)))move(note.recordingId(),invalid,"高等数学").andExpect(status().isBadRequest());
        mvc.perform(put("/api/recordings/"+note.recordingId()+"/course").contentType(MediaType.APPLICATION_JSON)
                .content("{\"course\":\"线性代数\"}")).andExpect(status().isBadRequest());
        move(note.recordingId(),"线性代数","旧的课程").andExpect(status().isConflict());
        move("missing","线性代数","高等数学").andExpect(status().isNotFound());
        assertThat(store.detail(note.recordingId())).isEqualTo(before);
        move(note.recordingId(),"线性代数","高等数学").andExpect(status().isOk());
        Detail moved=store.detail(note.recordingId());
        move(note.recordingId(),"线性代数","高等数学").andExpect(status().isOk());
        assertThat(store.detail(note.recordingId())).isEqualTo(moved);
        move(note.recordingId(),"离散数学","高等数学").andExpect(status().isConflict());
        assertThat(store.detail(note.recordingId())).isEqualTo(moved);
    }
    @Test void courseMoveWaitsForQueuedAndRunningTranscriptionAndSync() throws Exception {
        Recording recording=upload();
        move(recording.id(),"线性代数","高等数学").andExpect(status().isConflict());
        Job asr=store.claim();move(recording.id(),"线性代数","高等数学").andExpect(status().isConflict());
        store.completeTranscript(asr,transcript("课堂概念"),false);
        move(recording.id(),"线性代数","高等数学").andExpect(status().isOk());
        store.enqueue(recording.id(),Stage.SUMMARIZE);Job summary=store.claim();
        move(recording.id(),"离散数学","线性代数").andExpect(status().isConflict());
        Note note=store.completeSummary(summary,"课堂笔记");store.confirm(note.id(),note.revision());
        store.enqueue(recording.id(),Stage.SYNC);
        move(recording.id(),"离散数学","线性代数").andExpect(status().isConflict());
        Job sync=store.claim();move(recording.id(),"离散数学","线性代数").andExpect(status().isConflict());
        store.fail(sync,"同步失败");
        move(recording.id(),"离散数学","线性代数").andExpect(status().isOk());
        assertThat(store.recording(recording.id()).syncStatus()).isEqualTo("FAILED");
        assertThat(store.recording(recording.id()).lastError()).isEqualTo("同步失败");
    }
    @Test void uploadStatusDuplicateQueueAndAudioRange() throws Exception {
        Recording r=upload();assertThat(store.jobs(r.id())).hasSize(1);assertThat(r.transcriptStatus()).isEqualTo("QUEUED");
        mvc.perform(post("/api/recordings/"+r.id()+"/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"TRANSCRIBE\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/recordings/"+r.id()+"/audio").header("Range","bytes=0-43")).andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-43/32044"));
        mvc.perform(multipart("/api/recordings").file(new MockMultipartFile("file","bad.wav","audio/wav","bad".getBytes())).param("title","x").param("course","x").param("recordedOn","2026-10-13")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/notes/missing/confirm").header("Origin","https://other.example").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}")).andExpect(status().isForbidden());
    }

    @Test void exposesLiveTranscriptionProgressAndResetsItForANewAttempt() throws Exception {
        Recording recording=upload();Job first=store.claim();
        Path output=dataDirectory.resolve("asr").resolve(first.id());Files.createDirectories(output);
        Files.writeString(output.resolve("progress.json"),"{\"phase\":\"transcribing\",\"processedMs\":54000,\"durationMs\":67392,\"completedChunks\":2,\"totalChunks\":3}");
        mvc.perform(get("/api/recordings/"+recording.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recording.transcriptionProgress.percent").value(80))
                .andExpect(jsonPath("$.recording.transcriptionProgress.completedChunks").value(2));
        store.fail(first,"inference interrupted");store.enqueue(recording.id(),Stage.TRANSCRIBE);
        assertThat(store.recording(recording.id()).transcriptionProgress().percent()).isNull();
        mvc.perform(get("/api/recordings/"+recording.id()))
                .andExpect(jsonPath("$.recording.transcriptionProgress.percent").hasJsonPath())
                .andExpect(jsonPath("$.recording.transcriptionProgress.percent").value(org.hamcrest.Matchers.nullValue()));
        Job second=store.claim();assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(store.recording(recording.id()).transcriptionProgress().phase()).isEqualTo("starting");
        assertThat(Files.exists(output.resolve("progress.json"))).isTrue();
    }
    @Test void failedAttemptRetryPreservesTranscriptsAndLogs() throws Exception {
        Recording r=upload();Job first=store.claim();store.completeTranscript(first,transcript("第一版转录"),false);
        store.enqueue(r.id(),Stage.TRANSCRIBE);Job second=store.claim();store.artifact(second,"ASR_RESPONSE","partial-raw");store.fail(second,"模型失败");
        assertThat(store.currentTranscript(r.id()).text()).isEqualTo("第一版转录");
        Job retry=store.enqueue(r.id(),Stage.TRANSCRIBE);assertThat(retry.attempt()).isEqualTo(3);store.claim();store.completeTranscript(retry,transcript("修订转录"),false);
        assertThat(store.detail(r.id()).transcripts()).hasSize(2);assertThat(store.logs(r.id())).anyMatch(l->l.message().contains("模型失败"));
        assertThat(store.artifacts(r.id())).anyMatch(a->a.content().equals("partial-raw"));
    }
    @Test void editsVersionArchiveConfirmationAndFullTextSearch() throws Exception {
        Note n=prepareNote();store.confirm(n.id(),n.revision());
        Note edited=store.editNote(n.id(),new NoteEdit("导数重点","开头\n"+"课堂资料".repeat(100)+"\n稀有检索词","重点复习",n.revision()));
        assertThat(edited.confirmed()).isFalse();assertThat(store.versions(n.id())).hasSize(2);
        assertThatThrownBy(()->store.editNote(n.id(),new NoteEdit("旧版本","内容","课堂笔记",n.revision()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->store.enqueue(n.recordingId(),Stage.SYNC)).hasMessageContaining("请先确认");
        assertThat(store.recordings("稀有检索词","高等数学","重点复习","2026-10-13",true)).hasSize(1);
        assertThat(store.recordings("%",null,null,null,true)).isEmpty();
    }
    @Test void generationDoesNotOverwriteConcurrentManualEdit() throws Exception {
        Note n=prepareNote();store.enqueue(n.recordingId(),Stage.SUMMARIZE);Job job=store.claim();
        Note edited=store.editNote(n.id(),new NoteEdit(n.title(),"我的课堂理解",n.category(),n.revision()));
        store.artifact(job,"GENERATED_MARKDOWN","生成的笔记");
        assertThatThrownBy(()->store.completeSummary(job,"生成的笔记")).hasMessageContaining("已被编辑");
        assertThat(store.note(n.id()).markdown()).isEqualTo(edited.markdown());
    }
    @Test void chunkingNeverDropsTextAndKnowledgePromptDropsBoardOperations() throws Exception {
        String source="第一行\n"+"定义😀".repeat(2500)+"最后知识点";
        List<String> parts=KnowledgeExtractor.split(source,2000);
        assertThat(String.join("",parts)).isEqualTo(source);assertThat(parts).allMatch(p->p.length()<=2000);
        Note note=prepareNote();store.enqueue(note.recordingId(),Stage.SUMMARIZE);Job job=store.claim();
        settings.save(Map.of("llmChunkChars","2000"));
        Transcript t=new Transcript("t",note.recordingId(),"test",1000,source,List.of(new Segment(0,1000,source,"window")),Store.now());
        String result=extractor.summarize(job,store.recording(note.recordingId()),t);
        assertThat(llmInputs).anyMatch(s->s.contains("最后知识点"));assertThat(llmInputs.size()).isGreaterThan(2);
        assertThat(result).doesNotContain("私有推理");assertThat(KnowledgeExtractor.PROMPT).contains("板书动作","待核对");
        assertThat(store.artifacts(note.recordingId())).anyMatch(a->a.kind().equals("GENERATED_MARKDOWN"));
    }
    @Test void llmRejectsTruncatedAndThinkingContentAndKeepsRaw() throws Exception {
        Note n=prepareNote();store.enqueue(n.recordingId(),Stage.SUMMARIZE);Job job=store.claim();
        finish="length";assertThatThrownBy(()->llm.complete(job,"s","x")).isInstanceOf(LlmOutputLimitException.class).hasMessageContaining("输出长度限制");
        finish="stop";content="<think>internal</think>answer";assertThatThrownBy(()->llm.complete(job,"s","x")).hasMessageContaining("推理标记");
        assertThat(store.artifacts(n.recordingId())).anyMatch(a->a.content().contains("length"));
    }
    @Test void retryingLongSummaryCanSaveAndEditBeyondTheOldLimitWithoutRetranscription() throws Exception {
        Recording recording=upload();
        String original="知识".repeat(1990);
        store.completeTranscript(store.claim(),transcript(original),false);
        store.enqueue(recording.id(),Stage.SUMMARIZE);Job failed=store.claim();
        store.artifact(failed,"LLM_RESPONSE","previous-response");
        store.fail(failed,"分段摘要过长，无法安全合并");
        settings.save(Map.of("llmChunkChars","2000"));
        content="## 核心概念\n"+"知识解释".repeat(35000)+"末尾知识 [01:59:59]";
        store.enqueue(recording.id(),Stage.SUMMARIZE);Job retry=store.claim();
        String markdown=extractor.summarize(retry,store.recording(recording.id()),store.currentTranscript(recording.id()));
        Note note=store.completeSummary(retry,markdown);
        assertThat(markdown).hasSizeGreaterThan(250000).contains("末尾知识 [01:59:59]","# 第 2 / 2 段");
        assertThat(llmInputs).hasSize(2);
        assertThat(store.recording(recording.id()).summaryStatus()).isEqualTo("SUCCEEDED");
        assertThat(store.currentTranscript(recording.id()).text()).isEqualTo(original);
        assertThat(store.jobs(recording.id())).filteredOn(j->j.stage()==Stage.TRANSCRIBE).hasSize(1);
        assertThat(store.artifacts(recording.id())).anyMatch(a->a.content().equals("previous-response"));
        mvc.perform(put("/api/notes/"+note.id()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new NoteEdit(note.title(),markdown+"\n我的补充",note.category(),note.revision()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(note.revision()+1));
        assertThat(store.note(note.id()).markdown()).endsWith("我的补充");
        assertThat(store.versions(note.id())).hasSize(2);
    }
    @Test void settingsHideEncryptAndPreserveBlankCredentials() throws Exception {
        settings.save(Map.of("llmApiKey","local-secret-123","autoSummarize","false"));
        String stored=db.queryForObject("SELECT value FROM settings WHERE key='llmApiKey'",String.class);
        assertThat(stored).doesNotContain("local-secret-123");assertThat(settings.publicSettings()).doesNotContainKey("llmApiKey");
        settings.save(Map.of("llmApiKey",""));assertThat(settings.get("llmApiKey")).isEqualTo("local-secret-123");
        assertThatThrownBy(()->settings.save(Map.of("llmBaseUrl","http://remote.example/v1"))).hasMessageContaining("HTTPS");
        assertThat(settings.redact("oops local-secret-123")).isEqualTo("oops [REDACTED]");
    }
    @Test void systemPromptPersistsIsUsedAndResetOnlyChangesThisSetting() throws Exception {
        String custom="按照我的课程要求整理。".repeat(250);
        settings.save(Map.of("llmSystemPrompt",custom,"llmChunkChars","4000","llmApiKey","keep-key"));
        mvc.perform(get("/api/settings")).andExpect(jsonPath("$.llmSystemPrompt").value(custom));
        Note note=prepareNote();store.enqueue(note.recordingId(),Stage.SUMMARIZE);Job job=store.claim();
        extractor.summarize(job,store.recording(note.recordingId()),store.currentTranscript(note.recordingId()));
        assertThat(llmSystems).containsExactly(custom);
        mvc.perform(post("/api/settings/system-prompt/reset")).andExpect(status().isOk()).andExpect(jsonPath("$.llmSystemPrompt").value(KnowledgeExtractor.PROMPT));
        assertThat(settings.get("llmChunkChars")).isEqualTo("4000");assertThat(settings.get("llmApiKey")).isEqualTo("keep-key");
        assertThatThrownBy(()->settings.save(Map.of("llmSystemPrompt"," "))).hasMessageContaining("不能为空");
        assertThatThrownBy(()->settings.save(Map.of("llmSystemPrompt","字".repeat(20001)))).hasMessageContaining("20000");
    }
    @Test void aiChatSavesVersionsPersistsConversationAndQuestionsKeepConfirmation() throws Exception {
        Note note=prepareNote();store.confirm(note.id(),note.revision());
        mvc.perform(post("/api/notes/"+note.id()+"/chat").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AiChatRequest("把概念名称写清楚，保留时间",note.revision()))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        Job job=store.claim();assertThat(job.stage()).isEqualTo(Stage.REFINE);
        content="{\"reply\":\"已修改名称并保留时间\",\"edits\":[{\"before\":\"可导与连续\",\"after\":\"可导与连续的关系\"}]}";
        Note updated=store.completeRefinement(job,refiner.refine(job));
        assertThat(updated.markdown()).endsWith("可导与连续的关系 [00:00:00]");assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.confirmed()).isFalse();assertThat(store.versions(note.id()).getFirst().source()).isEqualTo("AI_CHAT");
        mvc.perform(get("/api/notes/"+note.id()+"/chat")).andExpect(jsonPath("$[0].changed").value(true)).andExpect(jsonPath("$[0].resultRevision").value(2));
        store.confirm(note.id(),updated.revision());
        store.enqueueAiChat(note.id(),new AiChatRequest("解释一下上次保留的来源时间",updated.revision()));Job question=store.claim();
        content="{\"reply\":\"表示原录音中的来源位置\",\"edits\":[]}";
        Note unchanged=store.completeRefinement(question,refiner.refine(question));
        assertThat(unchanged.revision()).isEqualTo(2);assertThat(unchanged.confirmed()).isTrue();
        assertThat(store.aiTurns(note.id())).hasSize(2);assertThat(store.versions(note.id())).hasSize(2);
        assertThat(llmInputs.getLast()).contains("把概念名称写清楚，保留时间","已修改名称并保留时间");
    }
    @Test void aiChatCannotOverwriteManualEditsAndItsInstructionCanBeRetried() throws Exception {
        Note note=prepareNote();
        store.enqueueAiChat(note.id(),new AiChatRequest("改写概念名称",note.revision()));Job job=store.claim();
        content="{\"reply\":\"已改写\",\"edits\":[{\"before\":\"可导与连续\",\"after\":\"导数与连续性\"}]}";
        Refinement result=refiner.refine(job);
        store.editNote(note.id(),new NoteEdit(note.title(),"手动保留的课堂理解",note.category(),note.revision()));
        assertThatThrownBy(()->store.completeRefinement(job,result)).hasMessageContaining("已被编辑");
        store.fail(job,"AI 对话期间笔记已被编辑");
        assertThat(store.note(note.id()).markdown()).isEqualTo("手动保留的课堂理解");
        assertThat(store.artifacts(note.recordingId())).anyMatch(a->a.kind().equals("GENERATED_MARKDOWN")&&a.content().contains("导数与连续性"));
        Job retry=store.enqueue(note.recordingId(),Stage.REFINE);
        assertThat(retry.noteRevision()).isEqualTo(2);assertThat(retry.attempt()).isEqualTo(2);
        assertThat(store.aiTurnForJob(retry.id()).message()).isEqualTo("改写概念名称");
    }
    @Test void invalidChatRequestsDoNotQueueWorkAndInvalidModelResultsDoNotChangeNotes() throws Exception {
        Note note=prepareNote();
        mvc.perform(post("/api/notes/"+note.id()+"/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"修改\",\"revision\":99}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/notes/"+note.id()+"/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \",\"revision\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(store.aiTurns(note.id())).isEmpty();
        store.enqueueAiChat(note.id(),new AiChatRequest("修改定义",note.revision()));Job job=store.claim();
        content="未按协议返回 JSON";
        assertThatThrownBy(()->refiner.refine(job)).hasMessageContaining("JSON");store.fail(job,"模型修改格式无效");
        assertThat(store.note(note.id()).markdown()).isEqualTo(note.markdown());
        assertThat(store.artifacts(note.recordingId())).anyMatch(a->a.kind().equals("LLM_RESPONSE")&&a.content().contains(content));
        assertThat(store.aiTurns(note.id()).getFirst().status()).isEqualTo("FAILED");
    }
    @Test void notionRetryAfterContentFailureUpdatesTheSamePage() throws Exception {
        Note n=prepareNote();store.confirm(n.id(),n.revision());store.enqueue(n.recordingId(),Stage.SYNC);Job first=store.claim();
        failMarkdown=true;assertThatThrownBy(()->notion.sync(first)).hasMessageContaining("503");store.fail(first,"Notion 503");
        assertThat(store.note(n.id()).notionPageId()).isEqualTo(PAGE);assertThat(store.note(n.id()).syncedRevision()).isNull();
        assertThatThrownBy(()->{store.enqueue(n.recordingId(),Stage.SYNC);store.editNote(n.id(),new NoteEdit(n.title(),"编辑",n.category(),n.revision()));}).hasMessageContaining("同步中");
        Job retry=store.claim();failMarkdown=false;notion.sync(retry);
        assertThat(creates.get()).isEqualTo(1);assertThat(store.note(n.id()).syncedRevision()).isEqualTo(n.revision());
    }
    @Test void uncertainNotionCreationDoesNotBlindlyCreateAgain() throws Exception {
        Note n=prepareNote();store.confirm(n.id(),n.revision());store.enqueue(n.recordingId(),Stage.SYNC);Job first=store.claim();
        uncertainCreate=true;assertThatThrownBy(()->notion.sync(first)).hasMessageContaining("500");store.fail(first,"创建结果不确定");
        store.enqueue(n.recordingId(),Stage.SYNC);Job retry=store.claim();
        assertThatThrownBy(()->notion.sync(retry)).hasMessageContaining("已阻止再次创建");store.fail(retry,"创建结果不确定");
        assertThat(creates.get()).isEqualTo(1);
        pageExists=true;uncertainCreate=false;store.enqueue(n.recordingId(),Stage.SYNC);notion.sync(store.claim());
        assertThat(creates.get()).isEqualTo(1);assertThat(store.note(n.id()).notionPageId()).isEqualTo(PAGE);
    }
    @Test void startupRecoveryMarksInterruptedJobsAndKeepsQueuedJobs() throws Exception {
        Recording first=upload();Job running=store.claim();store.artifact(running,"ASR_RESPONSE","partial-result");
        Recording queued=upload();
        new Store(db,json,dataDirectory).init();
        assertThat(store.jobs(first.id()).getFirst().status()).isEqualTo("FAILED");
        assertThat(store.jobs(queued.id()).getFirst().status()).isEqualTo("QUEUED");
        assertThat(store.artifacts(first.id())).anyMatch(a->a.content().equals("partial-result"));
    }
    @Test void newTranscriptKeepsExistingNoteButInvalidatesOldConfirmation() throws Exception {
        Note n=prepareNote();store.confirm(n.id(),n.revision());
        store.enqueue(n.recordingId(),Stage.TRANSCRIBE);Job job=store.claim();
        store.completeTranscript(job,transcript("新的课堂转录"),false);
        Note preserved=store.note(n.id());
        assertThat(preserved.markdown()).isEqualTo(n.markdown());assertThat(preserved.confirmed()).isFalse();
        assertThat(store.recording(n.recordingId()).summaryStatus()).isEqualTo("PENDING");
        assertThat(store.versions(n.id())).hasSize(1);assertThat(store.detail(n.recordingId()).transcripts()).hasSize(2);
    }
}
