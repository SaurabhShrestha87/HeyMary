package heymary.co.integrations.exception;

public class SyncException extends RuntimeException {
    private final String merchantId;
    private final String entityType;
    private final String entityId;

    public SyncException(String message, String merchantId, String entityType, String entityId) {
        super(message);
        this.merchantId = merchantId;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public SyncException(String message, Throwable cause, String merchantId, String entityType, String entityId) {
        super(message, cause);
        this.merchantId = merchantId;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }
}

