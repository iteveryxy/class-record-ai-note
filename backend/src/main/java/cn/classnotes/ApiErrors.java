package cn.classnotes;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason())); }
    @ExceptionHandler({IllegalArgumentException.class,java.time.format.DateTimeParseException.class,MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("message",e instanceof IllegalArgumentException?e.getMessage():"请求参数无效，请检查输入")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> large(Exception e) { return ResponseEntity.status(413).body(Map.of("message","单个录音不能超过 1GB")); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("message","资源不存在")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> other(Exception e) { log.error("API request failed",e); return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message","服务处理失败，请检查后端日志；已保存的数据仍然保留")); }
}
