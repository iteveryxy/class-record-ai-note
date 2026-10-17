package cn.classnotes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
class LocalRequestsTest {
    @Test void desktopSessionTokenProtectsLocalApi() throws Exception {
        var filter=new LocalRequests("session-secret");
        var request=new MockHttpServletRequest("GET","/api/health");var response=new MockHttpServletResponse();
        var called=new AtomicBoolean();filter.doFilter(request,response,(a,b)->called.set(true));
        assertThat(response.getStatus()).isEqualTo(401);assertThat(called.get()).isFalse();
        request=new MockHttpServletRequest("GET","/api/health");request.addHeader("Authorization","Bearer session-secret");
        response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->called.set(true));
        assertThat(response.getStatus()).isEqualTo(200);assertThat(called.get()).isTrue();
    }
}
