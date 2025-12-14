package heymary.co.integrations.model;

/**
 * Enum representing the field used to match Treez customers with Boomerangme cards.
 * This determines whether cards are fetched and linked based on phone number or email address.
 */
public enum CustomerMatchType {
    /**
     * Match customers and cards based on phone number
     */
    PHONE("phone", "Phone Number"),
    
    /**
     * Match customers and cards based on email address
     */
    EMAIL("email", "Email Address");

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
            return EMAIL; // Default to EMAIL if not specified
        }
        for (CustomerMatchType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown customer match type code: " + code);
    }
}
