package com.proxy.interceptor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReplayProtectionService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditService auditService;

    private static final String NONCE_PREFIX = "nonce:";
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);

    /*
    * Validate that a request is not a replay attack.
    * Returns true if the request is valid (not a replay), false otherwise.
     */
    public boolean validateRequest(String nonce,
                                   String timestamp,
                                   String requestBody,
                                   String username) {
        if (nonce == null || nonce.isBlank()) {
            log.warn("Replay protection: Missing nonce from user {}", username);
            return false;
        }

        // Check timestamp (request should be within 5 minutes)
        try {
            long requestTime = Long.parseLong(timestamp);
            long now = System.currentTimeMillis();
            long diff = Math.abs(now - requestTime);

            if (diff > NONCE_TTL.toMillis()) {
                log.warn("Replay protection: Request timestamp too old from user {}", username);
                return false;
            }
        } catch (NumberFormatException e) {
            log.warn("Replay protection: Invalid timestamp from user {}", username);
            return false;
        }

        // Check if nonce was already user (stored in Redis)
        String nonceKey = NONCE_PREFIX + nonce;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(nonceKey, "1", NONCE_TTL);

        if (isNew == null || !isNew) {
            log.warn("Replay protection: Duplication nonce detected from user {}", username);
            auditService.log(username, "replay_attack_detected",
                    "Duplicate nonce: " + nonce, null);
            return false;
        }

        return true;
    }

    /*
    * Generate a hash of the request for audit logging.
     */
    public String generateRequestHash(String method,
                                      String path,
                                      String body,
                                      String timestamp) {
        try {
            String data = method + ":" + path + ":" + body + ":" + timestamp;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /*
    * Check if a specific request hash was already processed
     */
    public boolean wasRequestProcessed(String requestHash) {
        return auditService.isReplayedRequest(requestHash);
    }
}
