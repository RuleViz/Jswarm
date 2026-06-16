package com.jswarm.adapter.lc4j.run;

import java.time.Duration;

public final class SwarmRunOptions {

    public static final int DEFAULT_MAX_TURNS = 10;
    public static final int DEFAULT_MAX_RECOVERY_ATTEMPTS = 2;
    public static final int DEFAULT_MAX_DELEGATE_DEPTH = 3;
    public static final Duration DEFAULT_MODEL_TIMEOUT = Duration.ofSeconds(60);
    public static final boolean DEFAULT_DELEGATE_STREAMING = true;

    private final int maxTurns;
    private final int maxRecoveryAttempts;
    private final int maxDelegateDepth;
    private final Duration modelTimeout;
    private final boolean delegateStreaming;

    private SwarmRunOptions(Builder builder) {
        this.maxTurns = builder.maxTurns;
        this.maxRecoveryAttempts = builder.maxRecoveryAttempts;
        this.maxDelegateDepth = builder.maxDelegateDepth;
        this.modelTimeout = builder.modelTimeout;
        this.delegateStreaming = builder.delegateStreaming;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public int maxRecoveryAttempts() {
        return maxRecoveryAttempts;
    }

    public int maxDelegateDepth() {
        return maxDelegateDepth;
    }

    public Duration modelTimeout() {
        return modelTimeout;
    }

    public boolean delegateStreaming() {
        return delegateStreaming;
    }

    public static SwarmRunOptions defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxTurns = DEFAULT_MAX_TURNS;
        private int maxRecoveryAttempts = DEFAULT_MAX_RECOVERY_ATTEMPTS;
        private int maxDelegateDepth = DEFAULT_MAX_DELEGATE_DEPTH;
        private Duration modelTimeout = DEFAULT_MODEL_TIMEOUT;
        private boolean delegateStreaming = DEFAULT_DELEGATE_STREAMING;

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxRecoveryAttempts(int maxRecoveryAttempts) {
            this.maxRecoveryAttempts = maxRecoveryAttempts;
            return this;
        }

        public Builder maxDelegateDepth(int maxDelegateDepth) {
            this.maxDelegateDepth = maxDelegateDepth;
            return this;
        }

        public Builder modelTimeout(Duration modelTimeout) {
            this.modelTimeout = modelTimeout;
            return this;
        }

        public Builder delegateStreaming(boolean delegateStreaming) {
            this.delegateStreaming = delegateStreaming;
            return this;
        }

        public SwarmRunOptions build() {
            if (maxTurns <= 0) {
                throw new IllegalArgumentException("maxTurns must be > 0");
            }
            if (maxRecoveryAttempts < 0) {
                throw new IllegalArgumentException("maxRecoveryAttempts must be >= 0");
            }
            if (maxDelegateDepth <= 0) {
                throw new IllegalArgumentException("maxDelegateDepth must be > 0");
            }
            if (modelTimeout == null || modelTimeout.isNegative() || modelTimeout.isZero()) {
                throw new IllegalArgumentException("modelTimeout must be positive");
            }
            return new SwarmRunOptions(this);
        }
    }
}
