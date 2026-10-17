package cn.classnotes;

import static cn.classnotes.Models.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.demo",havingValue="false",matchIfMissing=true)
public class CompatibleLlmProvider implements LlmProvider {
    private final JsonHttp http; private final Settings settings; private final Store store;
    public CompatibleLlmProvider(JsonHttp http, Settings settings, Store store) { this.http=http;this.settings=settings;this.store=store; }
    public String complete(Job job, String system, String content) throws Exception {
        if(settings.get("llmModel").isBlank()) throw new IllegalStateException("请在系统设置中配置大模型名称和 API 地址");
        Map<String,String> headers=new HashMap<>();
        if(!settings.get("llmApiKey").isBlank()) headers.put("Authorization","Bearer "+settings.get("llmApiKey"));
        var response=http.send("POST",settings.get("llmBaseUrl").replaceAll("/+$","")+"/chat/completions",headers,
                Map.of("model",settings.get("llmModel"),"messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",content)),"stream",false),Duration.ofMinutes(5));
        store.artifact(job,"LLM_RESPONSE",settings.redact(response.body()));
        JsonHttp.requireSuccess(response,"大模型");
        var choice=response.json().path("choices").path(0);
        if("length".equals(choice.path("finish_reason").asText())) throw new LlmOutputLimitException();
        if(!"stop".equals(choice.path("finish_reason").asText())) throw new IllegalStateException("模型未正常完成（可能达到长度限制），原始响应已保留；可减小分段长度后重试");
        var node=choice.path("message").path("content");
        if(!node.isTextual()||node.asText().isBlank()) throw new IllegalStateException("模型未返回正式回答 content；原始响应已保留");
        String text=node.asText().trim();
        if(text.matches("(?is).*</?(think|thinking|analysis|reasoning)>.*")) throw new IllegalStateException("模型 content 含推理标记，未作为正式笔记保存，请检查模型配置");
        if(text.startsWith("```markdown")&&text.endsWith("```")) text=text.substring(11,text.length()-3).trim();
        return text;
    }
}
