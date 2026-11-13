package com.lizhi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 * <p>
 * 开发态前端（Vite dev server，如 http://localhost:5173）和静态 dist 直接访问后端时会产生跨域请求；
 * 系统当前没有鉴权，因此这里对所有来源放开 CORS，不改变既有接口的鉴权/返回契约。
 * 网关（8080）转发时会把 service-agv 返回的 CORS 响应头透传给浏览器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
