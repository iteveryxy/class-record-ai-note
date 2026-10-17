package cn.classnotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonHttp {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    public JsonHttp(ObjectMapper json) { this.json=json; }
    public record Response(int status, String body, JsonNode json, String retryAfter) {}
    public Response send(String method, String url, Map<String,String> headers, Object body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Content-Type","application/json");
        headers.forEach(request::header);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        JsonNode parsed;
        try { parsed=json.readTree(response.body()); } catch(Exception e) { parsed=json.createObjectNode(); }
        return new Response(response.statusCode(),response.body(),parsed,response.headers().firstValue("Retry-After").orElse(""));
    }
    public static void requireSuccess(Response response, String service) {
        if (response.status()<200||response.status()>=300) {
            String detail="";
            if(response.json()!=null) {
                JsonNode node=response.json().path("detail");
                detail=node.isTextual()?node.asText():node.path("message").asText();
            }
            if(detail.length()>500) detail=detail.substring(0,500);
            throw new IllegalStateException(service+" HTTP "+response.status()+
                    (response.status()==429?"（限流，稍后可重试；Retry-After="+response.retryAfter()+"）":"；请检查配置及处理日志")+(detail.isBlank()?"":"："+detail));
        }
    }
}
