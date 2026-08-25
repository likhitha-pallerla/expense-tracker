package com.expensetracker.api.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Turns the {@code app.ai.*} block into {@link AiProperties}.
 *
 * <p>Separate from the beans it configures so that an install with no key
 * still starts: every class in this package is written to work with AI
 * switched off, which is the default and the normal case.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
}
