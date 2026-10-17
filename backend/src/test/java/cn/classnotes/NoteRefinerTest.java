package cn.classnotes;

import static cn.classnotes.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteRefinerTest {
    final Store store=mock(Store.class);final Settings settings=mock(Settings.class);final ObjectMapper json=new ObjectMapper();
    final Job job=new Job("job","recording",Stage.REFINE,"RUNNING",1,4,"","","",null);
    final AiTurn turn=new AiTurn("turn","job","精简说明，保留所有公式和时间",null,4,null,false,"RUNNING",null,"");
    @BeforeEach void setup() {
        when(settings.get("llmChunkChars")).thenReturn("2000");
        when(settings.get("llmSystemPrompt")).thenReturn("自定义提示词：侧重证明条件");
        when(store.aiTurnForJob("job")).thenReturn(turn);
        when(store.aiTurns("note")).thenReturn(List.of(new AiTurn("old","old-job","先保留原有编号","已保留编号",3,4L,true,"SUCCEEDED",null,""),turn));
    }
    void note(String markdown) {
        when(store.noteForRecording("recording")).thenReturn(new Note("note","recording","导数",markdown,"课堂笔记",4,null,null,null,null,null,"",""));
    }
    String answer(String before,String after) throws Exception {
        return json.writeValueAsString(Map.of("reply","已精简说明并保留公式","edits",List.of(Map.of("before",before,"after",after))));
    }
    @Test void exactEditPreservesFormulaAndUsesCustomPromptAndConversation() throws Exception {
        String formula="$$f'(x)=\\lim_{h\\to0}\\frac{f(x+h)-f(x)}{h}$$ [00:03:00]";
        note("## 概念\n繁冗的说明\n"+formula);
        LlmProvider llm=(j,system,input)->{
            assertThat(system).contains("自定义提示词：侧重证明条件",NoteRefiner.EDIT_RULES);
            assertThat(input).contains("先保留原有编号","已保留编号",turn.message());
            return answer("繁冗的说明","简洁的说明");
        };
        Refinement result=new NoteRefiner(llm,settings,store,json).refine(job);
        assertThat(result.markdown()).isEqualTo("## 概念\n简洁的说明\n"+formula);
        verify(store).artifact(job,"GENERATED_MARKDOWN",result.markdown());
    }
    @Test void questionsDoNotRequireChangingTheNote() throws Exception {
        note("原文公式 $a+b=c$");
        Refinement result=new NoteRefiner((j,s,c)->"{\"reply\":\"这里表示变量的关系\",\"edits\":[]}",settings,store,json).refine(job);
        assertThat(result.markdown()).isEqualTo("原文公式 $a+b=c$");
        assertThat(result.reply()).contains("本次未改动笔记","变量的关系");
    }
    @Test void missingOrAmbiguousAnchorsNeverRewriteTheNote() throws Exception {
        note("重复文本 重复文本");
        String edits=answer("重复文本","新文本");
        assertThatThrownBy(()->new NoteRefiner((j,s,c)->edits,settings,store,json).refine(job)).hasMessageContaining("无法唯一匹配");
        String missing=answer("不存在的原文","新文本");
        assertThatThrownBy(()->new NoteRefiner((j,s,c)->missing,settings,store,json).refine(job)).hasMessageContaining("无法唯一匹配");
        verify(store,never()).artifact(eq(job),eq("GENERATED_MARKDOWN"),anyString());
    }
    @Test void overlappingEditsAreRejectedAsAWhole() throws Exception {
        note("abcde");
        String edits=json.writeValueAsString(Map.of("reply","修改","edits",List.of(Map.of("before","abc","after","x"),Map.of("before","bcd","after","y"))));
        assertThatThrownBy(()->new NoteRefiner((j,s,c)->edits,settings,store,json).refine(job)).hasMessageContaining("重叠");
        verify(store,never()).artifact(eq(job),eq("GENERATED_MARKDOWN"),anyString());
    }
    @Test void longNotesKeepEveryCharacterOutsideTheLastRequestedEdit() throws Exception {
        String original="课堂知识😀".repeat(4000)+"尾部待修改说明 [03:59:00]";
        note(original);List<String> inputs=new ArrayList<>();
        LlmProvider llm=(j,s,c)->{
            String part=c.split("<note_part>\n",2)[1].split("\n</note_part>",2)[0];
            inputs.add(part);
            return part.contains("尾部待修改说明")?answer("尾部待修改说明","尾部精简说明"):"{\"reply\":\"此段保持原样\",\"edits\":[]}";
        };
        Refinement result=new NoteRefiner(llm,settings,store,json).refine(job);
        assertThat(String.join("",inputs)).isEqualTo(original);
        assertThat(inputs).hasSizeGreaterThan(8).allMatch(part->part.length()<=2000);
        assertThat(result.markdown()).isEqualTo(original.replace("尾部待修改说明","尾部精简说明"));
    }
    @Test void invalidJsonOrTrailingTextIsNotApplied() {
        note("原文");
        for(String response:List.of("正文而非 JSON","null","{\"reply\":\"ok\",\"edits\":[]} garbage")) {
            assertThatThrownBy(()->new NoteRefiner((j,s,c)->response,settings,store,json).refine(job)).hasMessageContaining("JSON");
        }
        verify(store,never()).artifact(eq(job),eq("GENERATED_MARKDOWN"),anyString());
    }
    @Test void aLaterModelFailureDoesNotPublishPartialEdits() throws Exception {
        note("首段待修改"+"课".repeat(4000));
        int[] calls={0};
        LlmProvider llm=(j,s,c)->{if(calls[0]++>0)throw new LlmOutputLimitException();return answer("首段待修改","首段已修改");};
        assertThatThrownBy(()->new NoteRefiner(llm,settings,store,json).refine(job)).isInstanceOf(LlmOutputLimitException.class);
        verify(store,never()).artifact(eq(job),eq("GENERATED_MARKDOWN"),anyString());
    }
}
