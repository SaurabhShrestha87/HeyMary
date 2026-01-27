package heymary.co.integrations.model;

/**
 * Enum representing the field used to match Treez customers with Boomerangme cards.
 * Only phone number is used for matching customers.
 */
public enum CustomerMatchType {
    /**
     * Match customers and cards based on phone number
     */
    PHONE("phone", "Phone Number");

    private final String code;
    private final String displayName;

    CustomerMatchType(String code, String displayName) {
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
     * Get CustomerMatchType from code string
     */
    public static CustomerMatchType fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return PHONE; // Default to PHONE if not specified
        }
        for (CustomerMatchType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown customer match type code: " + code);
    }
}
