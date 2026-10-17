package cn.classnotes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Additive, transactional migrations; the application never downgrades a newer database. */
public final class DatabaseMigrations {
    public static final int CURRENT_VERSION=3;
    private DatabaseMigrations() {}
    public static void migrate(Path data) throws SQLException,IOException {
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) {
            execute(connection,"PRAGMA busy_timeout=10000");
            int version=integer(connection,"PRAGMA user_version");
            if(version>CURRENT_VERSION) throw new SQLException("数据格式版本 "+version+" 高于此程序支持的 "+CURRENT_VERSION+"；请安装较新的课记，不能降级打开此数据目录");
            boolean hasData=integer(connection,"SELECT count(*) FROM sqlite_master WHERE type='table' AND name='recordings'")>0;
            if(hasData && integer(connection,"SELECT count(*) FROM sqlite_master WHERE type='table' AND name='settings'")>0 &&
                    integer(connection,"SELECT count(*) FROM settings WHERE key IN ('llmApiKey','notionToken','workerToken') AND value<>''")>0 && !Files.exists(data.resolve("settings.key")))
                throw new IOException("数据目录包含加密凭证，但缺少 settings.key。请从备份恢复密钥，程序没有修改数据库。");
            if(version==CURRENT_VERSION) return;
            if(hasData) {
                Path backupDir=data.resolve("backups");Files.createDirectories(backupDir);
                String name="schema-"+version+"-"+Instant.now().toString().replace(":","-")+"-"+UUID.randomUUID();
                Path backup=backupDir.resolve(name+".db");
                execute(connection,"VACUUM INTO '"+backup.toString().replace("'","''")+"'");
                if(Files.exists(data.resolve("settings.key"))) Files.copy(data.resolve("settings.key"),backupDir.resolve(name+".key"),StandardCopyOption.COPY_ATTRIBUTES);
            }
            connection.setAutoCommit(false);
            try {
                if(version<1) {
                    ScriptUtils.executeSqlScript(connection,new ClassPathResource("schema.sql"));
                    execute(connection,"PRAGMA user_version=1");
                }
                if(version<2) {
                    // Early MVP copies may predate the uncertain-Notion-creation field.
                    if(!column(connection,"notes","notion_pending_target")) execute(connection,"ALTER TABLE notes ADD COLUMN notion_pending_target TEXT");
                    ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migrations/V002__desktop_metadata.sql"));
                    execute(connection,"PRAGMA user_version=2");
                }
                if(version<3) {
                    ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migrations/V003__note_ai_chat.sql"));
                    execute(connection,"PRAGMA user_version=3");
                }
                connection.commit();
            } catch(Exception e) { connection.rollback();throw new SQLException("数据升级失败，已回滚；原录音和笔记保留，请检查日志与 backups 目录",e); }
        }
    }
    private static boolean column(Connection connection,String table,String name) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery("PRAGMA table_info("+table+")")) {
            while(result.next()) if(name.equals(result.getString("name"))) return true;
            return false;
        }
    }
    private static int integer(Connection connection,String sql) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) { return result.next()?result.getInt(1):0; }
    }
    private static void execute(Connection connection,String sql) throws SQLException { try(var statement=connection.createStatement()) { statement.execute(sql); } }
}
