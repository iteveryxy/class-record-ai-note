package cn.classnotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClassNotesApplication {
    public static void main(String[] args) { SpringApplication.run(ClassNotesApplication.class, args); }
}
