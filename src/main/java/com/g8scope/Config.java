package com.g8scope;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable SDK configuration. Build with {@link #builder()}.
 * Framework adapters construct this from their own config.
 */
public final class Config {

    private final String key;
    private final String endpoint;
    private final String environment;
    private final String release;
    private final long timeoutMillis;
    private final int maxFrames;
    private final int maxMessage;
    private final List<String> ignoreExceptions;
    private final List<String> scrubKeys;
    private final String basePath;
    private final boolean enabled;
    private final String sdkName;
    private final String sdkVersion;

    private Config(Builder b) {
        this.key = b.key;
        this.endpoint = b.endpoint;
        this.environment = b.environment;
        this.release = b.release;
        this.timeoutMillis = b.timeoutMillis;
        this.maxFrames = b.maxFrames;
        this.maxMessage = b.maxMessage;
        this.ignoreExceptions = List.copyOf(b.ignoreExceptions);
        this.scrubKeys = List.copyOf(b.scrubKeys);
        this.basePath = b.basePath;
        this.enabled = b.enabled;
        this.sdkName = b.sdkName;
        this.sdkVersion = b.sdkVersion;
    }

    public String key() {
        return key;
    }

    public String endpoint() {
        return endpoint;
    }

    public String environment() {
        return environment;
    }

    public String release() {
        return release;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public int maxFrames() {
        return maxFrames;
    }

    public int maxMessage() {
        return maxMessage;
    }

    public List<String> ignoreExceptions() {
        return ignoreExceptions;
    }

    public List<String> scrubKeys() {
        return scrubKeys;
    }

    public String basePath() {
        return basePath;
    }

    public boolean enabled() {
        return enabled;
    }

    public String sdkName() {
        return sdkName;
    }

    public String sdkVersion() {
        return sdkVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link Config}. */
    public static final class Builder {
        private String key = null;
        private String endpoint = "https://ingest.scope.g8suite.com";
        private String environment = "production";
        private String release = null;
        private long timeoutMillis = 3000L;
        private int maxFrames = 30;
        private int maxMessage = 2000;
        private List<String> ignoreExceptions = new ArrayList<>();
        private List<String> scrubKeys = new ArrayList<>();
        private String basePath = "";
        private boolean enabled = true;
        private String sdkName = "g8scope-java";
        private String sdkVersion = "1.0.0";

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder release(String release) {
            this.release = release;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder maxFrames(int maxFrames) {
            this.maxFrames = maxFrames;
            return this;
        }

        public Builder maxMessage(int maxMessage) {
            this.maxMessage = maxMessage;
            return this;
        }

        public Builder ignoreExceptions(List<String> ignoreExceptions) {
            this.ignoreExceptions = ignoreExceptions != null ? new ArrayList<>(ignoreExceptions) : new ArrayList<>();
            return this;
        }

        public Builder scrubKeys(List<String> scrubKeys) {
            this.scrubKeys = scrubKeys != null ? new ArrayList<>(scrubKeys) : new ArrayList<>();
            return this;
        }

        public Builder basePath(String basePath) {
            this.basePath = basePath != null ? basePath : "";
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder sdkName(String sdkName) {
            this.sdkName = sdkName;
            return this;
        }

        public Builder sdkVersion(String sdkVersion) {
            this.sdkVersion = sdkVersion;
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }
}
