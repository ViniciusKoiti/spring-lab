package lab.springlab.enrichment.exception;

public class ExternalEnrichmentException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;

    public ExternalEnrichmentException(String message, Integer statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
