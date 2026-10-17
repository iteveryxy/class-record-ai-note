package cn.classnotes;

import static cn.classnotes.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeExtractorTest {
    final Store store=mock(Store.class);
    final Settings settings=mock(Settings.class);
    final Recording recording=mock(Recording.class);
    final Job job=new Job("job","recording",Stage.SUMMARIZE,"RUNNING",1,0,"","","",null);

    @BeforeEach void setup() {
        when(settings.get("llmChunkChars")).thenReturn("2000");
        when(recording.course()).thenReturn("高等数学");
        when(recording.title()).thenReturn("长课堂");
    }
    Transcript transcript(String text) {
        return new Transcript("transcript","recording","test",7200000,text,List.of(),"");
    }

    @Test void verboseOutputsFinishWithEveryPartAndNoRecursiveMerge() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        String formula="$$f'(x)=\\lim_{h\\to0}\\frac{f(x+h)-f(x)}{h}$$\n\n```java\n// # 保留原始代码\nint n=1;\n```";
        LlmProvider llm=(j,s,input)->{
            assertThat(input).contains("<transcript>").doesNotContain("<notes>");
            int part=calls.incrementAndGet();
            return "## 核心概念\n第 "+part+" 段唯一知识点 [01:59:59]\n"+"详细解释".repeat(700)+"\n"+formula;
        };
        String markdown=new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(10000)));
        assertThat(calls.get()).isEqualTo(5);
        for(int i=1;i<=5;i++) assertThat(markdown).contains("第 "+i+" 段唯一知识点");
        assertThat(markdown).contains(formula,"[01:59:59]","# 第 5 / 5 段");
        verify(store,times(5)).artifact(eq(job),eq("SUMMARY_PART"),anyString());
        verify(store).artifact(job,"GENERATED_MARKDOWN",markdown);
    }

    @Test void shortPartsHaveAtMostOneWholeDocumentMerge() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        LlmProvider llm=(j,s,input)->{
            calls.incrementAndGet();
            return input.contains("<notes>")?"## 核心概念\n合并后的全部知识":"## 核心概念\n分段知识";
        };
        String markdown=new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(4000)));
        assertThat(calls.get()).isEqualTo(3);
        assertThat(markdown).isEqualTo("## 核心概念\n合并后的全部知识");
    }

    @Test void expandingMergeFallsBackToIntactParts() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        LlmProvider llm=(j,s,input)->input.contains("<notes>")?"扩写".repeat(2000):"知识点"+calls.incrementAndGet();
        String markdown=new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(4000)));
        assertThat(markdown).contains("知识点1","知识点2").doesNotContain("扩写");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void truncatedOptionalMergeStillKeepsAllCompletedParts() throws Exception {
        AtomicInteger parts=new AtomicInteger();
        LlmProvider llm=(j,s,input)->{
            if(input.contains("<notes>")) throw new LlmOutputLimitException();
            return "完整段落"+parts.incrementAndGet();
        };
        String markdown=new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(4000)));
        assertThat(markdown).contains("完整段落1","完整段落2");
        verify(store).artifact(job,"GENERATED_MARKDOWN",markdown);
    }

    @Test void failedExtractionKeepsFinishedPartsButDoesNotPublishAnIncompleteNote() {
        AtomicInteger parts=new AtomicInteger();
        LlmProvider llm=(j,s,input)->{
            if(parts.incrementAndGet()==2) throw new LlmOutputLimitException();
            return "第一段完整知识";
        };
        assertThatThrownBy(()->new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(4000))))
                .isInstanceOf(LlmOutputLimitException.class);
        verify(store).artifact(eq(job),eq("SUMMARY_PART"),contains("第一段完整知识"));
        verify(store,never()).artifact(eq(job),eq("GENERATED_MARKDOWN"),anyString());
    }

    @Test void networkErrorsAreNotSilentlyTreatedAsSuccessfulMerges() {
        LlmProvider llm=(j,s,input)->{
            if(input.contains("<notes>")) throw new IOException("connection failed");
            return "完整知识";
        };
        assertThatThrownBy(()->new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(4000))))
                .isInstanceOf(IOException.class);
        verify(store,times(2)).artifact(eq(job),eq("SUMMARY_PART"),anyString());
        verify(store,never()).artifact(eq(job),eq("GENERATED_MARKDOWN"),anyString());
    }

    @Test void anOversizedDocumentIsPreservedBeforeReportingTheStorageLimit() {
        String part="知识".repeat(MAX_NOTE_CHARS/4);
        LlmProvider llm=(j,s,input)->part;
        assertThatThrownBy(()->new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript("课".repeat(4000))))
                .hasMessageContaining("200 万字符");
        ArgumentCaptor<String> saved=ArgumentCaptor.forClass(String.class);
        verify(store).artifact(eq(job),eq("GENERATED_MARKDOWN"),saved.capture());
        assertThat(saved.getValue()).hasSizeGreaterThan(MAX_NOTE_CHARS).contains(part);
    }

    @Test void emptyTranscriptDoesNotCallTheModel() throws Exception {
        LlmProvider llm=mock(LlmProvider.class);
        assertThatThrownBy(()->new KnowledgeExtractor(llm,settings,store).summarize(job,recording,transcript(" \n")))
                .hasMessageContaining("转录内容为空");
        verifyNoInteractions(llm);
    }
}
