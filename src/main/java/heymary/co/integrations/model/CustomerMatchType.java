package heymary.co.integrations.model;

/**
 * Enum representing the field used to match Treez customers with Boomerangme cards.
 * This determines whether cards are fetched and linked based on phone number, email address, or both.
 */
public enum CustomerMatchType {
    /**
     * Match customers and cards based on phone number
     */
    PHONE("phone", "Phone Number"),
    
    /**
     * Match customers and cards based on email address
     */
    EMAIL("email", "Email Address"),
    
    /**
     * Match customers and cards based on both phone number AND email address
     * This provides the most accurate matching by requiring both fields to match
     */
    BOTH("both", "Phone and Email");

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
            return BOTH; // Default to BOTH if not specified
        }
        for (CustomerMatchType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown customer match type code: " + code);
    }
}
