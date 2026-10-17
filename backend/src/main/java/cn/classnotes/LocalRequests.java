package cn.classnotes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A third-party website must not be able to submit forms to the local service. */
@Component
public class LocalRequests extends OncePerRequestFilter {
    private final String token;
    public LocalRequests(@Value("${app.api-token:}") String token) { this.token=token; }
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String origin=request.getHeader("Origin");
        if(request.getRequestURI().startsWith("/api/") && !token.isBlank() && !MessageDigest.isEqual(
                ("Bearer "+token).getBytes(StandardCharsets.UTF_8), java.util.Objects.toString(request.getHeader("Authorization"),"").getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"桌面会话已失效，请重新启动课记\"}");return;
        }
        boolean mutating=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        String localOrigin="http://"+request.getServerName()+":"+request.getServerPort();
        if(request.getRequestURI().startsWith("/api/") && mutating && origin!=null &&
                !origin.equals(localOrigin)&&!Set.of("http://127.0.0.1:5173","http://localhost:5173").contains(origin)) {
            response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"仅允许本地工作空间提交操作\"}");return;
        }
        chain.doFilter(request,response);
    }
}
