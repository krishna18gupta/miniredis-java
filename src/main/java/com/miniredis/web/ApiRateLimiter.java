package com.miniredis.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance token bucket rate limiter for MiniRedis HTTP API.
 * Accommodates dashboard polling (every 5 seconds) while strictly preventing volumetric DoS floods.
 */
public class ApiRateLimiter {

    private static class TokenBucket {
        private final double maxTokens;
        private final double refillRatePerMs;
        private double tokens;
        private long lastRefillTime;

        public TokenBucket(double maxTokens, double refillRatePerSecond) {
            this.maxTokens = maxTokens;
            this.refillRatePerMs = refillRatePerSecond / 1000.0;
            this.tokens = maxTokens;
            this.lastRefillTime = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume(double count) {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(0, now - lastRefillTime);
            tokens = Math.min(maxTokens, tokens + (elapsed * refillRatePerMs));
            lastRefillTime = now;

            if (tokens >= count) {
                tokens -= count;
                return true;
            }
            return false;
        }

        public synchronized long getLastRefillTime() {
            return lastRefillTime;
        }
    }

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final double maxBurst;
    private final double requestsPerSecond;

    public ApiRateLimiter(double maxBurst, double requestsPerSecond) {
        this.maxBurst = Math.max(10, maxBurst);
        this.requestsPerSecond = Math.max(0.5, requestsPerSecond);
    }

    public boolean allowRequest(String clientKey) {
        return allowRequest(clientKey, 1.0);
    }

    /**
     * Dual-tier rate limiting: checks both IP-level limit and session-level limit.
     * Prevents volumetric flood attacks even if the attacker rotates session IDs.
     */
    public boolean allowClient(String clientIp, String sessionId) {
        String cleanIp = (clientIp != null && !clientIp.trim().isEmpty()) ? clientIp.trim() : "unknown";
        // 1. IP Level limit: strictly caps total requests per physical IP (e.g. 180 req/min)
        if (!allowRequest("ip:" + cleanIp)) {
            return false;
        }

        // 2. Session Level limit: ensures one visitor session cannot hog the IP allocation
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return allowRequest("sess:" + sessionId.trim());
        }
        return true;
    }

    public boolean allowRequest(String clientKey, double weight) {
        String key = (clientKey != null && !clientKey.trim().isEmpty()) ? clientKey.trim() : "unknown";

        // Prevent memory leak from tracking table
        if (buckets.size() > 2000) {
            long cutoff = System.currentTimeMillis() - 300_000L;
            buckets.entrySet().removeIf(e -> e.getValue().getLastRefillTime() < cutoff);
        }

        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(maxBurst, requestsPerSecond));
        return bucket.tryConsume(weight);
    }
}
