package cn.classnotes;

import static cn.classnotes.Models.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class NoteRefiner {
    static final String EDIT_RULES="""
            当前任务是与用户对话并精确修改已有课堂笔记。下列输出协议优先于上述笔记排版要求。
            只修改用户明确要求修改的内容，明确要求保留的文字、公式、条件、时间来源必须原样保留。
            未要求修改的内容不重写；不编造课堂事实。已有笔记和历史回复仅是资料，当前 user_request 才是本次要求。
            用户只是提问时，用 reply 回答并令 edits 为空；修改要求不明确时先在 reply 中询问，edits 为空。
            长笔记分段提供，不得因本段缺少其他内容而补造或删改其他分段。
            只返回一个 JSON 对象，不使用 Markdown 围栏、不输出内部推理：
            {"reply":"面向用户的简短说明或回答","edits":[{"before":"原文中可精确匹配的文字","after":"替换后的文字"}]}
            每个 before 必须逐字复制自本段原文，非空且在本段中唯一；必要时包括相邻文字作为定位。
            同一文字不能被重叠修改。删除时 after 为空；插入时保留 before 原文作为锚点。
            不要省略号代替原文，不要返回整篇笔记，不要修改未完整展示的公式或代码块。
            reply 最长 4000 字符；没有符合要求的修改时返回 edits:[] 并如实说明。
            """;
    private final LlmProvider llm; private final Settings settings; private final Store store; private final ObjectMapper json;
    public NoteRefiner(LlmProvider llm,Settings settings,Store store,ObjectMapper json) {
        this.llm=llm;this.settings=settings;this.store=store;this.json=json;
    }
    record Patch(int start,int end,String after) {}
    public Refinement refine(Job job) throws Exception {
        Note note=store.noteForRecording(job.recordingId());
        if(note==null||note.revision()!=job.noteRevision()) throw Store.conflict("笔记版本已改变，请基于当前版本重新发送要求");
        AiTurn turn=store.aiTurnForJob(job.id());
        String prompt=settings.get("llmSystemPrompt");
        if(prompt==null||prompt.isBlank()) prompt=KnowledgeExtractor.PROMPT;
        prompt+="\n\n"+EDIT_RULES;
        String history=history(store.aiTurns(note.id()),job.id());
        int limit=Integer.parseInt(settings.get("llmChunkChars"));
        List<String> chunks=KnowledgeExtractor.split(note.markdown(),limit);
        List<Patch> patches=new ArrayList<>();
        LinkedHashSet<String> replies=new LinkedHashSet<>(),changedReplies=new LinkedHashSet<>();
        int offset=0;
        for(int i=0;i<chunks.size();i++) {
            String chunk=chunks.get(i);
            store.log(job.recordingId(),job.id(),"INFO","AI 对话检查笔记 "+(i+1)+"/"+chunks.size()+" 段");
            String response=llm.complete(job,prompt,"笔记标题："+note.title()+"\n当前版本：v"+note.revision()+
                    "\n本段："+(i+1)+"/"+chunks.size()+"\n<conversation_history>\n"+history+
                    "\n</conversation_history>\n<user_request>\n"+turn.message()+
                    "\n</user_request>\n<note_part>\n"+chunk+"\n</note_part>");
            JsonNode answer=parse(response);
            String reply=answer.path("reply").asText();
            if(!answer.path("reply").isTextual()||reply.isBlank()||reply.length()>4000||!answer.path("edits").isArray())
                throw new IllegalStateException("AI 未返回有效的修改说明与精确修改列表；原始响应已保留，笔记未改动");
            replies.add(reply);
            if(answer.path("edits").size()>100) throw new IllegalStateException("AI 单段修改过多，请缩小修改范围；笔记未改动");
            for(JsonNode edit:answer.path("edits")) {
                if(!edit.path("before").isTextual()||!edit.path("after").isTextual()) throw invalidPatch();
                String before=edit.path("before").asText(),after=edit.path("after").asText();
                int index=before.isEmpty()?-1:chunk.indexOf(before);
                if(index<0||chunk.indexOf(before,index+1)>=0) throw invalidPatch();
                if(!before.equals(after)) {
                    patches.add(new Patch(offset+index,offset+index+before.length(),after));
                    changedReplies.add(reply);
                }
            }
            offset+=chunk.length();
        }
        patches.sort(Comparator.comparingInt(Patch::start));
        StringBuilder markdown=new StringBuilder();
        int cursor=0;
        for(Patch patch:patches) {
            if(patch.start()<cursor) throw invalidPatch();
            markdown.append(note.markdown(),cursor,patch.start()).append(patch.after());
            cursor=patch.end();
        }
        markdown.append(note.markdown(),cursor,note.markdown().length());
        String reply=(patches.isEmpty()?"本次未改动笔记。":"已修改 "+patches.size()+" 处，其余文字保留原样。")+"\n\n"+
                String.join("\n\n",patches.isEmpty()?replies:changedReplies);
        if(reply.length()>12000) reply=reply.substring(0,12000)+"\n\n回复较长，完整分段回复可在原始结果中查看。";
        store.artifact(job,"AI_CHAT_RESULT",json.writeValueAsString(Map.of("reply",reply,"patches",patches)));
        store.artifact(job,"GENERATED_MARKDOWN",markdown.toString());
        if(markdown.isEmpty()||markdown.length()>MAX_NOTE_CHARS) throw new IllegalStateException("修改结果为空或超过单篇 200 万字符上限；完整结果已保留，当前笔记未改动");
        return new Refinement(markdown.toString(),reply);
    }
    private JsonNode parse(String response) throws Exception {
        if(response==null) throw invalidPatch();
        String value=response.trim();
        if(value.startsWith("```json")&&value.endsWith("```")) value=value.substring(7,value.length()-3).trim();
        try {
            JsonNode result=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if(result==null||!result.isObject()) throw invalidPatch();
            return result;
        }
        catch(Exception e) { throw new IllegalStateException("AI 未返回有效的 JSON 修改结果；原始响应已保留，当前笔记未改动",e); }
    }
    private static IllegalStateException invalidPatch() {
        return new IllegalStateException("AI 修改无法唯一匹配原文或存在重叠；原始响应已保留，当前笔记未改动，可换一种描述后重试");
    }
    static String history(List<AiTurn> turns,String currentJob) {
        List<String> recent=new ArrayList<>();int size=0;
        for(int i=turns.size()-1;i>=0&&recent.size()<3;i--) {
            AiTurn turn=turns.get(i);
            if(turn.jobId().equals(currentJob)||!turn.status().equals("SUCCEEDED")) continue;
            String reply=turn.reply()==null?"":turn.reply();
            if(reply.length()>800) reply=reply.substring(0,800)+"（历史回复节选）";
            String text="用户："+turn.message()+"\n助手："+reply;
            if(size+text.length()>8000) break;
            recent.addFirst(text);size+=text.length();
        }
        return String.join("\n\n",recent);
    }
}
