package com.expensetracker.api.connections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class ConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(ConnectionConfig.class);

    /**
     * The key is read once, here, so nothing else in the application has a
     * reason to hold the raw value.
     */
    @Bean
    public TokenCipher tokenCipher(@Value("${app.encryption.key:}") String base64Key) {
        TokenCipher cipher = TokenCipher.fromBase64Key(base64Key);
        if (!cipher.isConfigured()) {
            log.warn("TOKEN_ENCRYPTION_KEY is not set. Everything else works; "
                    + "connecting a mailbox will be refused until it is.");
        }
        return cipher;
    }
}
