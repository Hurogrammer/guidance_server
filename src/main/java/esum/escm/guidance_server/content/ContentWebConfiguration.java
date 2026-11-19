package esum.escm.guidance_server.content;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ContentWebConfiguration implements WebMvcConfigurer {
    static final String ADMIN_ATTRIBUTE = "contentAdmin";
    private final ContentAdminClient auth;

    public ContentWebConfiguration(ContentAdminClient auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor(auth))
                .addPathPatterns("/api/files", "/api/files/**", "/api/blocks/**", "/api/menus/*/blocks");
    }

    static HandlerInterceptor adminInterceptor(ContentAdminClient auth) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD") && !request.getMethod().equals("OPTIONS")) {
                    request.setAttribute(ADMIN_ATTRIBUTE, auth.requireAdmin(request.getHeader("Authorization")));
                }
                return true;
            }
        };
    }
}
