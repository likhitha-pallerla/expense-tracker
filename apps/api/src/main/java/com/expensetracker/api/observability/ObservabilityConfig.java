package com.expensetracker.api.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the access log.
 *
 * <p>Ordered ahead of the rate limiter for a reason worth stating: when an
 * interceptor's {@code preHandle} returns false, Spring calls {@code
 * afterCompletion} only on the interceptors that ran <em>before</em> it. A
 * request refused with 429 therefore appears in the log only if the access log
 * is registered first — and a throttled request is exactly the one someone will
 * ask about.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ObservabilityConfig implements WebMvcConfigurer {

    private final AccessLogInterceptor accessLog;

    public ObservabilityConfig(AccessLogInterceptor accessLog) {
        this.accessLog = accessLog;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessLog).addPathPatterns("/**");
    }
}
