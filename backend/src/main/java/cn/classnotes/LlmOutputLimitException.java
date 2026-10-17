package cn.classnotes;

/** A truncated answer must never be treated as a complete extraction. */
public class LlmOutputLimitException extends IllegalStateException {
    public LlmOutputLimitException() {
        super("模型回答达到输出长度限制，原始响应已保留；可减小分段长度后重试");
    }
}
