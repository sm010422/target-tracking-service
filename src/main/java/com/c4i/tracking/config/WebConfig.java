package com.c4i.tracking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST API에 대한 CORS 허용. WebSocket(/ws)은 WebSocketConfig에서 이미
 * setAllowedOriginPatterns("*")로 열려 있어 별도 처리 불필요.
 *
 * 대시보드가 static/index.html(같은 오리진)로만 제공되던 동안은 CORS가 필요 없었지만,
 * 별도 리포(c4i-dashboard-frontend, Vercel 배포)로 프론트가 분리되면서 이 서비스가
 * 다른 오리진에서의 fetch 요청을 받아야 하게 됐다. 포트폴리오 성격상 전체 오리진을
 * 허용했다 -- 인증이 붙는 시점에는 특정 Vercel 도메인으로 좁혀야 한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*");
    }
}
