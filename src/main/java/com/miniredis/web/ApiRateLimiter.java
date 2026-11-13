package com.miniredis.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe token bucket rate limiter for MiniRedis HTTP API.
 * Protects public endpoints from denial of service and command flooding.
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

        public synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(0, now - lastRefillTime);
            tokens = Math.min(maxTokens, tokens + (elapsed * refillRatePerMs));
            lastRefillTime = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
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
        this.maxBurst = Math.max(1, maxBurst);
        this.requestsPerSecond = Math.max(0.1, requestsPerSecond);
    }

    public boolean allowRequest(String clientIp) {
        String key = (clientIp != null && !clientIp.trim().isEmpty()) ? clientIp.trim() : "unknown";

        // Prevent memory leak from IP tracking table
        if (buckets.size() > 1000) {
            long cutoff = System.currentTimeMillis() - 300_000L;
            buckets.entrySet().removeIf(e -> e.getValue().getLastRefillTime() < cutoff);
        }

        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(maxBurst, requestsPerSecond));
        return bucket.tryConsume();
    }
}
