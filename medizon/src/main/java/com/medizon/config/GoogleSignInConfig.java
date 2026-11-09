package com.medizon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Sign in with Google on the website, set up only when GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are set. Asks
 * Google for the name and email only ("openid email profile"), which needs no review by Google.
 */
@Configuration
@ConditionalOnExpression("!'${medizon.google.client-id:}'.isBlank() and !'${medizon.google.client-secret:}'.isBlank()")
public class GoogleSignInConfig {

    public static final String REGISTRATION = "google";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(@Value("${medizon.google.client-id}") String clientId,
                                                                     @Value("${medizon.google.client-secret}") String secret) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION)
                .clientId(clientId.trim())
                .clientSecret(secret.trim())
                .scope("openid", "email", "profile")
                .build());
    }
}
