package cn.classnotes;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig implements WebMvcConfigurer {
    @Bean
    public Path dataDirectory(@Value("${app.data-dir}") String dir) throws IOException {
        Path root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("audio"));
        Files.createDirectories(root.resolve("asr"));
        return root.toRealPath();
    }

    // Recovery of RUNNING jobs is safe only while this process owns the data directory.
    @Bean(destroyMethod = "close")
    public DataDirectoryLock dataLock(Path dataDirectory) throws IOException {
        return new DataDirectoryLock(dataDirectory.resolve("backend.lock"));
    }

    @Bean
    public DataSource dataSource(Path dataDirectory, DataDirectoryLock lock) throws Exception {
        Class.forName("org.sqlite.JDBC");
        DatabaseMigrations.migrate(dataDirectory);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dataDirectory.resolve("classnotes.db"));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        config.addDataSourceProperty("busy_timeout", "10000");
        config.addDataSourceProperty("journal_mode", "WAL");
        return new HikariDataSource(config);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://127.0.0.1:5173", "http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT");
    }

    static class DataDirectoryLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        DataDirectoryLock(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) { channel.close(); throw new IOException("此数据目录已有后端进程运行"); }
        }
        public void close() throws IOException { lock.release(); channel.close(); }
    }
}
