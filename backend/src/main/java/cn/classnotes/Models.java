package cn.classnotes;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class Models {
    public static final int MAX_NOTE_CHARS=2_000_000;
    private Models() {}
    public enum Stage { TRANSCRIBE, SUMMARIZE, REFINE, SYNC }
    public record Recording(String id, String title, String course, String recordedOn, String category,
                            String filename, long size, Long durationMs, String transcriptStatus,
                            String summaryStatus, String syncStatus, String lastError, String createdAt,
                            String updatedAt, String noteId, String noteTitle, String preview, boolean confirmed,
                            TranscriptionProgress transcriptionProgress) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record TranscriptionProgress(String phase, String message, Integer percent, long processedMs,
                                        long durationMs, int completedChunks, int totalChunks) {}
    public record Note(String id, String recordingId, String title, String markdown, String category,
                       long revision, Long confirmedRevision, Long syncedRevision, String notionPageId,
                       String notionUrl, String notionTarget, String createdAt, String updatedAt) {
        public boolean confirmed() { return confirmedRevision != null && confirmedRevision == revision; }
    }
    public record Job(String id, String recordingId, Stage stage, String status, int attempt,
                      long noteRevision, String createdAt, String startedAt, String finishedAt, String error) {}
    public record Segment(long startMs, long endMs, String text, String timestampKind) {}
    public record Transcript(String id, String recordingId, String provider, long durationMs,
                             String text, List<Segment> segments, String createdAt) {}
    public record AsrResult(String provider, long durationMs, String text, List<Segment> segments, JsonNode raw) {}
    public record LogEntry(long id, String jobId, String level, String message, String createdAt) {}
    public record Version(long id, long revision, String title, String markdown, String category,
                          String source, String createdAt) {}
    public record Artifact(long id, String jobId, String kind, String content, String createdAt) {}
    public record Detail(Recording recording, Note note, Transcript transcript, List<Job> jobs,
                         List<LogEntry> logs, List<Version> versions, List<Transcript> transcripts) {}
    public record NoteEdit(@NotBlank @Size(max = 200) String title,
                           @NotBlank @Size(max = MAX_NOTE_CHARS) String markdown,
                           @NotBlank @Size(max = 80) String category, long revision) {}
    public record RevisionRequest(long revision) {}
    public record CourseMove(@NotBlank @Size(max=120) String course,
                             @NotBlank @Size(max=120) String expectedCourse) {}
    public record AiChatRequest(@NotBlank @Size(max=5000) String message,long revision) {}
    public record AiTurn(String id,String jobId,String message,String reply,long baseRevision,Long resultRevision,
                         boolean changed,String status,String error,String createdAt) {}
    public record Refinement(String markdown,String reply) {}
    public record ActionRequest(Stage stage) {}
}
