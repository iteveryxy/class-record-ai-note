package cn.classnotes;

import static cn.classnotes.Models.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotionSync {
    private final Settings settings; private final Store store; private final JsonHttp http; private final String baseUrl;
    public NotionSync(Settings settings, Store store, JsonHttp http, @Value("${app.notion-base-url}") String baseUrl) {
        this.settings=settings;this.store=store;this.http=http;this.baseUrl=baseUrl.replaceAll("/+$","");
    }
    private JsonNode request(Job job, String method, String endpoint, Object body) throws Exception {
        var response=http.send(method,baseUrl+endpoint,Map.of("Authorization","Bearer "+settings.get("notionToken"),"Notion-Version","2026-03-11"),body,Duration.ofSeconds(90));
        store.artifact(job,"NOTION_RESPONSE",settings.redact(response.body()));
        JsonHttp.requireSuccess(response,"Notion");
        if(response.status()==202||response.json()==null||response.json().path("object").asText().equals("async_task"))
            throw new IllegalStateException("Notion 返回异步任务，尚未确认同步完成；请查看原始响应，稍后重试");
        return response.json();
    }
    public void sync(Job job) throws Exception {
        if(settings.demo()) throw new IllegalStateException("演示模式不写入 Notion；请以正式模式启动并配置 Integration Token");
        if(settings.get("notionToken").isBlank()) throw new IllegalStateException("请配置 Notion Integration Token 并授权数据库");
        Note note=store.noteForRecording(job.recordingId());
        if(note==null||!note.confirmed()||note.revision()!=job.noteRevision()) throw Store.conflict("请确认当前版本后再同步");
        String target=resolveTarget(job);
        JsonNode schema=request(job,"GET","/data_sources/"+target,null).path("properties");
        checkProperty(schema,settings.get("notionTitleProperty"),"title");
        checkProperty(schema,settings.get("notionSourceProperty"),"rich_text");
        checkProperty(schema,settings.get("notionCourseProperty"),"rich_text");
        checkProperty(schema,settings.get("notionDateProperty"),"date");
        checkProperty(schema,settings.get("notionCategoryProperty"),"select");
        String sourceId="classnotes:"+note.id();
        var found=request(job,"POST","/data_sources/"+target+"/query",Map.of("filter",Map.of("property",settings.get("notionSourceProperty"),"rich_text",Map.of("equals",sourceId)),"page_size",100));
        JsonNode results=found.path("results");
        if(!results.isArray()) throw new IllegalStateException("Notion 查重响应格式无效");
        if(results.size()>1||found.path("has_more").asBoolean()) throw new IllegalStateException("Notion 存在多个相同 Source ID，请先核对重复页面");
        String pageId=target.equals(note.notionTarget())?note.notionPageId():null;
        if(results.size()==1) {
            String remoteId=results.get(0).path("id").asText();
            if(pageId!=null&&!pageId.equals(remoteId)) throw new IllegalStateException("本地页面映射与 Notion Source ID 不一致，请先核对");
            pageId=remoteId; store.bindNotion(note,pageId,results.get(0).path("url").asText(),target);
        }
        Recording rec=store.recording(note.recordingId());
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put(settings.get("notionTitleProperty"),Map.of("title",richText(note.title())));
        properties.put(settings.get("notionSourceProperty"),Map.of("rich_text",richText(sourceId)));
        properties.put(settings.get("notionCourseProperty"),Map.of("rich_text",richText(rec.course())));
        properties.put(settings.get("notionDateProperty"),Map.of("date",Map.of("start",rec.recordedOn())));
        properties.put(settings.get("notionCategoryProperty"),Map.of("select",Map.of("name",note.category())));
        if(pageId==null) {
            if(store.pendingNotionCreation(note.id(),target)) throw new IllegalStateException("上次 Notion 创建结果不确定，本次查重未发现页面，已阻止再次创建。请稍后重试；确认远端未创建后可重置创建状态。");
            store.markNotionCreation(note.id(),target);
            JsonNode created=request(job,"POST","/pages",Map.of("parent",Map.of("type","data_source_id","data_source_id",target),"properties",properties));
            pageId=created.path("id").asText();
            if(pageId.isBlank()) throw new IllegalStateException("Notion 未返回 page ID，创建结果不确定");
            store.bindNotion(note,pageId,created.path("url").asText(),target);
            store.log(rec.id(),job.id(),"INFO","已创建 Notion 页面并保存映射");
        } else {
            JsonNode remote=request(job,"GET","/pages/"+pageId,null);
            if(remote.path("in_trash").asBoolean()||remote.path("archived").asBoolean()) throw new IllegalStateException("Notion 页面已归档或删除，请在 Notion 恢复后重试");
            JsonNode parent=remote.path("parent");
            if(!target.equals(parent.path("data_source_id").asText())) throw new IllegalStateException("Notion 页面已移动到其他数据库，停止同步");
            request(job,"PATCH","/pages/"+pageId,Map.of("properties",properties));
        }
        // Replace is idempotent. Keep Notion's default protection for child pages/databases.
        request(job,"PATCH","/pages/"+pageId+"/markdown",Map.of("type","replace_content","replace_content",Map.of("new_str",note.markdown()),"allow_async",false));
        store.completeSync(job,note);
    }
    private String resolveTarget(Job job) throws Exception {
        String target=settings.get("notionDataSourceId");
        if(!target.isBlank()) return canonicalId(target);
        String database=settings.get("notionDatabaseId");
        if(database.isBlank()) throw new IllegalStateException("请配置 Notion 数据库 ID 或数据源 ID");
        JsonNode sources=request(job,"GET","/databases/"+canonicalId(database),null).path("data_sources");
        if(!sources.isArray()||sources.size()!=1) throw new IllegalStateException("数据库包含多个或没有数据源，请在设置中填写准确的数据源 ID");
        return canonicalId(sources.get(0).path("id").asText());
    }
    private static String canonicalId(String id) {
        String clean=id.replace("-","");
        if(!clean.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Notion UUID 无效");
        return (clean.substring(0,8)+"-"+clean.substring(8,12)+"-"+clean.substring(12,16)+"-"+clean.substring(16,20)+"-"+clean.substring(20)).toLowerCase();
    }
    private static void checkProperty(JsonNode schema,String name,String type) {
        if(!type.equals(schema.path(name).path("type").asText())) throw new IllegalStateException("Notion 数据源字段「"+name+"」必须为 "+type+" 类型");
    }
    private static List<Map<String,Object>> richText(String content) { return List.of(Map.of("type","text","text",Map.of("content",content))); }
}
