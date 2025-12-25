package lab.springlab.enrichment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lab.enrichment")
public class EnrichmentProperties {

    private String externalBaseUrl;
    private int batchSize = 50;
    private int maxConcurrency = 5;
    private int maxRetries = 3;
    private long backoffMs = 500;
    private long httpTimeoutMs = 1500;
    private long stuckThresholdMs = 30000;
    private long schedulerFixedDelayMs = 5000;

    public String getExternalBaseUrl() {
        return externalBaseUrl;
    }

    public void setExternalBaseUrl(String externalBaseUrl) {
        this.externalBaseUrl = externalBaseUrl;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }

    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(long httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }

    public long getStuckThresholdMs() {
        return stuckThresholdMs;
    }

    public void setStuckThresholdMs(long stuckThresholdMs) {
        this.stuckThresholdMs = stuckThresholdMs;
    }

    public long getSchedulerFixedDelayMs() {
        return schedulerFixedDelayMs;
    }

    public void setSchedulerFixedDelayMs(long schedulerFixedDelayMs) {
        this.schedulerFixedDelayMs = schedulerFixedDelayMs;
    }
}
