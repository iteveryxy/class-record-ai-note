package cn.classnotes;
import static cn.classnotes.Models.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JobRunnerRetentionTest {
    @Test void cleanupFailureDoesNotTurnCommittedTranscriptionIntoFailure() throws Exception {
        Store store=mock(Store.class);Settings settings=mock(Settings.class);AsrProvider asr=mock(AsrProvider.class);AudioStorage audio=mock(AudioStorage.class);
        Job job=new Job("job","recording",Stage.TRANSCRIBE,"RUNNING",1,0,"",null,null,null);
        when(store.claim()).thenReturn(job);when(store.audioPath("recording")).thenReturn(Path.of("audio.wav"));
        AsrResult result=mock(AsrResult.class);when(asr.transcribe(eq(job),any())).thenReturn(result);
        doThrow(new IOException("locked")).when(audio).afterTranscription("recording");
        JobRunner runner=new JobRunner(store,settings,asr,mock(KnowledgeExtractor.class),mock(NotionSync.class),mock(NoteRefiner.class),audio);
        runner.onReady();runner.tick();
        var order=inOrder(store,audio);order.verify(store).completeTranscript(job,result,false);order.verify(audio).afterTranscription("recording");
        verify(store,never()).fail(any(),anyString());verify(store).log(eq("recording"),eq("job"),eq("WARN"),contains("转录已保存"));
    }
    @Test void failedAsrDoesNotAttemptAudioCleanup() throws Exception {
        Store store=mock(Store.class);Settings settings=mock(Settings.class);AsrProvider asr=mock(AsrProvider.class);AudioStorage audio=mock(AudioStorage.class);
        Job job=new Job("job","recording",Stage.TRANSCRIBE,"RUNNING",1,0,"",null,null,null);
        when(store.claim()).thenReturn(job);when(store.audioPath("recording")).thenReturn(Path.of("audio.wav"));
        when(asr.transcribe(eq(job),any())).thenThrow(new IOException("ASR unavailable"));when(settings.redact(anyString())).thenAnswer(i->i.getArgument(0));
        JobRunner runner=new JobRunner(store,settings,asr,mock(KnowledgeExtractor.class),mock(NotionSync.class),mock(NoteRefiner.class),audio);
        runner.onReady();runner.tick();verifyNoInteractions(audio);verify(store).fail(job,"ASR unavailable");
    }
}
