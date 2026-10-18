package cn.classnotes;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Settings {
    private static final Set<String> SECRETS = Set.of("llmApiKey", "notionToken", "workerToken");
    private final JdbcTemplate db;
    private final Map<String,String> defaults = new LinkedHashMap<>();
    private final byte[] encryptionKey;
    private final boolean demo;
    private final boolean desktop;
    public Settings(JdbcTemplate db, Environment env, Path dataDirectory, Store store) throws Exception {
        this.db = db; demo = env.getProperty("app.demo",Boolean.class,false);desktop=env.getProperty("app.desktop",Boolean.class,false);
        defaults.put("workerUrl",env.getProperty("app.worker-url"));
        defaults.put("workerToken",env.getProperty("app.worker-token",""));
        defaults.put("llmBaseUrl",env.getProperty("app.llm-base-url"));
        defaults.put("llmModel",env.getProperty("app.llm-model",""));
        defaults.put("llmApiKey",env.getProperty("app.llm-api-key",""));
        defaults.put("llmChunkChars","6000"); defaults.put("autoSummarize","true");
        defaults.put("retainRecordings","true");
        defaults.put("llmSystemPrompt",KnowledgeExtractor.PROMPT);
        defaults.put("notionToken",env.getProperty("app.notion-token",""));
        defaults.put("notionDatabaseId",env.getProperty("app.notion-database-id",""));
        defaults.put("notionDataSourceId",env.getProperty("app.notion-data-source-id",""));
        defaults.put("notionTitleProperty","Name"); defaults.put("notionSourceProperty","Source ID");
        defaults.put("notionCourseProperty","Course"); defaults.put("notionDateProperty","Date");
        defaults.put("notionCategoryProperty","Category");
        Path keyPath=dataDirectory.resolve("settings.key");
        if (!Files.exists(keyPath)) {
            byte[] key=new byte[32]; new SecureRandom().nextBytes(key); Files.write(keyPath,key);
        }
        encryptionKey=Files.readAllBytes(keyPath);
        if (encryptionKey.length!=32) throw new IllegalStateException("settings.key 无效；请从备份恢复密钥");
    }
    public boolean demo() { return demo; }
    public String get(String key) {
        if(desktop && Set.of("workerUrl","workerToken").contains(key)) return defaults.get(key);
        String value=db.query("SELECT value FROM settings WHERE key=?",(r,i)->r.getString(1),key).stream().findFirst().orElse(null);
        return value==null?defaults.getOrDefault(key,""):(SECRETS.contains(key)?decrypt(value):value);
    }
    public boolean autoSummarize() { return Boolean.parseBoolean(get("autoSummarize")); }
    public boolean llmReady() { return demo || !get("llmModel").isBlank(); }
    public Map<String,Object> publicSettings() {
        Map<String,Object> result=new LinkedHashMap<>();
        defaults.keySet().forEach(key -> { if(SECRETS.contains(key)) result.put(key+"Configured",!get(key).isBlank()); else result.put(key,get(key)); });
        result.put("demo",demo); return result;
    }
    @Transactional
    public void save(Map<String,String> input) {
        for (var entry:input.entrySet()) {
            String key=entry.getKey(), value=entry.getValue();
            if(desktop && Set.of("workerUrl","workerToken").contains(key)) continue;
            if (!defaults.containsKey(key)) throw new IllegalArgumentException("未知设置："+key);
            if (value==null || value.length()>(key.equals("llmSystemPrompt")?20000:2000)) throw new IllegalArgumentException("设置值过长或无效；System Prompt 最多 20000 字符");
            value=value.trim();
            if(key.equals("llmSystemPrompt")&&value.isBlank()) throw new IllegalArgumentException("System Prompt 不能为空，可使用恢复默认");
            // Blank secret means keep current. Explicit removal is handled by a separate flag in UI.
            if (SECRETS.contains(key) && value.isBlank()) continue;
            if (key.endsWith("Url")) validateUrl(value);
            if (key.equals("llmChunkChars")) { int n=Integer.parseInt(value); if(n<2000||n>12000) throw new IllegalArgumentException("总结分段长度须为 2000–12000 字符"); }
            if (key.equals("autoSummarize") && !Set.of("true","false").contains(value)) throw new IllegalArgumentException("自动整理设置无效");
            if (key.equals("retainRecordings") && !Set.of("true","false").contains(value)) throw new IllegalArgumentException("录音保存设置无效");
            if ((key.equals("notionDatabaseId")||key.equals("notionDataSourceId"))&&!value.isBlank()&&!value.matches("[0-9a-fA-F-]{32,36}")) throw new IllegalArgumentException("Notion ID 应为数据库或数据源 UUID");
            if (key.endsWith("Property") && (value.isBlank()||value.length()>80)) throw new IllegalArgumentException("Notion 字段名应为 1–80 字符");
            db.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",key,SECRETS.contains(key)?encrypt(value):value);
        }
    }
    @Transactional
    public Map<String,Object> resetSystemPrompt() {
        db.update("DELETE FROM settings WHERE key='llmSystemPrompt'");
        return publicSettings();
    }
    public static void validateUrl(String value) {
        URI uri=URI.create(value);
        if (uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost()))))
            throw new IllegalArgumentException("服务地址须为 HTTPS 或本机 HTTP 地址，且不含用户名、查询或片段");
    }
    public String redact(String value) {
        if (value==null) return "处理失败";
        for (String key:SECRETS) { String secret=get(key); if(!secret.isBlank()) value=value.replace(secret,"[REDACTED]"); }
        return value;
    }
    private String encrypt(String value) {
        try {
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));
            byte[] ciphertext=cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined=new byte[iv.length+ciphertext.length]; System.arraycopy(iv,0,combined,0,iv.length); System.arraycopy(ciphertext,0,combined,iv.length,ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch(Exception e) { throw new IllegalStateException("凭证加密失败",e); }
    }
    private String decrypt(String value) {
        try {
            byte[] combined=Base64.getDecoder().decode(value);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,combined,0,12));
            return new String(cipher.doFinal(combined,12,combined.length-12),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("无法解密凭证，请恢复 settings.key 或重新配置",e); }
    }
}
