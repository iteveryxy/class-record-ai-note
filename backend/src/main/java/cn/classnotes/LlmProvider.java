package cn.classnotes;

import static cn.classnotes.Models.*;

public interface LlmProvider {
    String complete(Job job, String system, String content) throws Exception;
}
