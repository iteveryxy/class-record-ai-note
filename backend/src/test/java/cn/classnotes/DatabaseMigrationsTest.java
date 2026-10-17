package cn.classnotes;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DatabaseMigrationsTest {
    @TempDir Path data;
    void legacy() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("schema.sql"));
            c.createStatement().execute("INSERT INTO recordings(id,title,course,recorded_on,category,filename,stored_name,size,created_at,updated_at) VALUES('r','旧课堂','数学','2026-10-13','课堂笔记','a.wav','a.wav',1,'t','t')");
        }
        Files.write(data.resolve("settings.key"),new byte[32]);
    }
    @Test void oldMvpDatabaseIsBackedUpAndPreserved() throws Exception {
        legacy();DatabaseMigrations.migrate(data);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) {
            var version=c.createStatement().executeQuery("PRAGMA user_version");assertThat(version.next()).isTrue();assertThat(version.getInt(1)).isEqualTo(DatabaseMigrations.CURRENT_VERSION);
            var row=c.createStatement().executeQuery("SELECT title FROM recordings WHERE id='r'");assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("旧课堂");
        }
        try(var files=Files.list(data.resolve("backups"))) { assertThat(files.toList()).hasSize(2); }
        DatabaseMigrations.migrate(data);
        try(var files=Files.list(data.resolve("backups"))) { assertThat(files.toList()).hasSize(2); }
    }
    @Test void newerDataIsNeverDowngraded() throws Exception {
        legacy();try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) { c.createStatement().execute("PRAGMA user_version=99"); }
        assertThatThrownBy(()->DatabaseMigrations.migrate(data)).hasMessageContaining("不能降级");
        assertThat(data.resolve("backups")).doesNotExist();
    }
    @Test void desktopV2UpgradeAddsConversationStorageAndKeepsExistingRows() throws Exception {
        legacy();DatabaseMigrations.migrate(data);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) {
            c.createStatement().execute("DROP TABLE note_ai_turns");
            c.createStatement().execute("PRAGMA user_version=2");
            c.createStatement().execute("UPDATE app_metadata SET value='2' WHERE key='storage_format'");
        }
        DatabaseMigrations.migrate(data);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) {
            var table=c.createStatement().executeQuery("SELECT count(*) FROM note_ai_turns");assertThat(table.next()).isTrue();assertThat(table.getInt(1)).isZero();
            var row=c.createStatement().executeQuery("SELECT title FROM recordings WHERE id='r'");assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("旧课堂");
        }
        try(var files=Files.list(data.resolve("backups"))) {assertThat(files.toList()).hasSize(4);}
    }
    @Test void missingCredentialKeyStopsMigrationBeforeChangingData() throws Exception {
        legacy();Files.delete(data.resolve("settings.key"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("classnotes.db"))) { c.createStatement().execute("INSERT INTO settings(key,value) VALUES('llmApiKey','encrypted')"); }
        assertThatThrownBy(()->DatabaseMigrations.migrate(data)).hasMessageContaining("settings.key");
        assertThat(data.resolve("backups")).doesNotExist();
    }
}
