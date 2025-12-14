package heymary.co.integrations.model;

/**
 * Enum representing different POS integration types.
 * Each integration type has its own API and data synchronization logic.
 */
public enum IntegrationType {
    /**
     * Treez POS integration
     */
    TREEZ("treez", "Treez"),
    
    /**
     * Dutchie POS integration
     */
    DUTCHIE("dutchie", "Dutchie");

    private final String code;
    private final String displayName;

    IntegrationType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get IntegrationType from code string
     */
    public static IntegrationType fromCode(String code) {
        for (IntegrationType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown integration type code: " + code);
    }
}

