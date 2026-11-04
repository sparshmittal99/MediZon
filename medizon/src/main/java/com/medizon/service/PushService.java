package com.medizon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medizon.repository.NotificationDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Phone notifications for the Android app through Firebase Cloud Messaging (free). Off until FIREBASE_SERVICE_ACCOUNT
 * holds the Firebase service account JSON (raw or base64). Uses the FCM HTTP v1 API with an OAuth token signed here,
 * so no Google SDK is needed. A token Firebase says is gone is removed from its device.
 */
@Slf4j
@Service
public class PushService {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final NotificationDeviceRepository devices;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final String projectId;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey key;

    private volatile String accessToken;
    private volatile Instant accessTokenExpires = Instant.EPOCH;

    public PushService(NotificationDeviceRepository devices, ObjectMapper mapper,
                       @Value("${medizon.push.service-account:}") String serviceAccount) {
        this.devices = devices;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.http = RestClient.builder().requestFactory(factory).build();
        JsonNode account = parse(serviceAccount);
        this.projectId = account == null ? null : account.path("project_id").asText(null);
        this.clientEmail = account == null ? null : account.path("client_email").asText(null);
        this.tokenUri = account == null ? null : account.path("token_uri").asText("https://oauth2.googleapis.com/token");
        this.key = account == null ? null : privateKey(account.path("private_key").asText(""));
        if (isEnabled()) log.info("Phone notifications on (Firebase project {})", projectId);
    }

    public boolean isEnabled() { return projectId != null && clientEmail != null && key != null; }

    /** One data message to one phone; the app builds the notification. Never throws. */
    @Async
    public void send(String token, Map<String, String> data) {
        if (!isEnabled() || token == null || token.isBlank()) return;
        try {
            Map<String, Object> body = Map.of("message", Map.of(
                    "token", token,
                    "data", data,
                    "android", Map.of("priority", "HIGH", "ttl", "86400s")));
            http.post().uri("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            String reply = e.getResponseBodyAsString();
            if (e.getStatusCode().value() == 404 || reply.contains("UNREGISTERED") || reply.contains("registration token is not a valid")) {
                devices.findByPushToken(token).forEach(d -> { d.setPushToken(null); devices.save(d); });
                log.info("Removed a phone's push token Firebase no longer knows");
            } else {
                log.warn("Phone notification refused ({}): {}", e.getStatusCode().value(), reply);
            }
        } catch (Exception e) {
            log.warn("Phone notification failed: {}", e.getMessage());
        }
    }

    /** An OAuth access token for FCM, from a JWT signed with the service account's key; reused for about an hour. */
    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(120))) return accessToken;
        long now = Instant.now().getEpochSecond();
        String header = b64(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = b64(mapper.writeValueAsBytes(Map.of("iss", clientEmail, "scope", SCOPE, "aud", tokenUri,
                "iat", now, "exp", now + 3600)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String jwt = header + "." + claims + "." + b64(signer.sign());

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwt);
        JsonNode reply = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        accessToken = reply.path("access_token").asText();
        accessTokenExpires = Instant.now().plusSeconds(reply.path("expires_in").asLong(3600));
        return accessToken;
    }

    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    private JsonNode parse(String config) {
        if (config == null || config.isBlank()) return null;
        String json = config.trim();
        try {
            if (!json.startsWith("{")) json = new String(Base64.getDecoder().decode(json), StandardCharsets.UTF_8);
            return mapper.readTree(json);
        } catch (Exception e) {
            log.warn("FIREBASE_SERVICE_ACCOUNT isn't a service account JSON (raw or base64): phone notifications are off");
            return null;
        }
    }

    static PrivateKey privateKey(String pem) {
        try {
            String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                    .replace("\\n", "").replaceAll("\\s", "");
            if (body.isEmpty()) return null;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            return null;
        }
    }
}
