package com.itsjool.aperture.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aperture.rate-limit")
public class ApertureRateLimitProperties {
    private boolean enabled = true;
    private String backend = "memory";
    private final Limit ip = new Limit(100, 100, 60);
    private final Limit user = new Limit(50, 50, 60);
    private final Limit tenant = new Limit(500, 500, 60);
    private final Valkey valkey = new Valkey();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public Limit getIp() {
        return ip;
    }

    public Limit getUser() {
        return user;
    }

    public Limit getTenant() {
        return tenant;
    }

    public Valkey getValkey() {
        return valkey;
    }

    public static final class Limit {
        private int capacity;
        private int refillTokens;
        private int windowSeconds;

        public Limit(int capacity, int refillTokens, int windowSeconds) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.windowSeconds = windowSeconds;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillTokens() {
            return refillTokens;
        }

        public void setRefillTokens(int refillTokens) {
            this.refillTokens = refillTokens;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    public static final class Valkey {
        private String host = "127.0.0.1";
        private int port = 6379;
        private String libraryName = "aperture_rate_limit";
        private String functionName = "consume";
        private String keyPrefix = "aperture:rate-limit:";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getLibraryName() {
            return libraryName;
        }

        public void setLibraryName(String libraryName) {
            this.libraryName = libraryName;
        }

        public String getFunctionName() {
            return functionName;
        }

        public void setFunctionName(String functionName) {
            this.functionName = functionName;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }
}
