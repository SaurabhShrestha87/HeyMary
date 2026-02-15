package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a Boomerangme loyalty card.
 * Each card has a one-to-one relationship with a Customer in the POS system.
 */
@Entity
@Table(name = "cards", indexes = {
    @Index(name = "idx_cards_serial_number", columnList = "serial_number"),
    @Index(name = "idx_cards_cardholder_id", columnList = "cardholder_id"),
    @Index(name = "idx_cards_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_cards_status", columnList = "status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_cards_cardholder_id", columnNames = {"cardholder_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(name = "serial_number", length = 100)
    private String serialNumber; // Optional - may not be present when fetching from API

    @Column(name = "cardholder_id", nullable = false, unique = true, length = 100)
    private String cardholderId; // Required - primary identifier for cards

    @Column(name = "card_type", length = 50)
    private String cardType; // e.g., "reward", "stamp", "discount"

    @Column(name = "device_type", length = 50)
    private String deviceType; // e.g., "Google Pay", "Apple Wallet", "PWA"

    @Column(name = "template_id", nullable = false)
    private Integer templateId; // Boomerangme template ID

    // Many-to-One relationship with Template
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", referencedColumnName = "template_id", insertable = false, updatable = false)
    private Template template;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "not_installed"; // "not_installed", "installed"

    // Cardholder Information
    @Column(name = "cardholder_email", length = 255)
    private String cardholderEmail;

    @Column(name = "cardholder_phone", length = 50)
    private String cardholderPhone;

    @Column(name = "cardholder_first_name", length = 100)
    private String cardholderFirstName;

    @Column(name = "cardholder_last_name", length = 100)
    private String cardholderLastName;

    @Column(name = "cardholder_birth_date")
    private LocalDate cardholderBirthDate;

    @Column(name = "cardholder_license_expiration")
    private LocalDate cardholderLicenseExpiration;

    // Card Metrics
    @Column(name = "bonus_balance")
    @Builder.Default
    private Integer bonusBalance = 0;

    @Column(name = "count_visits")
    @Builder.Default
    private Integer countVisits = 0;

    @Column(name = "balance")
    private Integer balance;

    @Column(name = "number_stamps_total")
    private Integer numberStampsTotal;

    @Column(name = "number_rewards_unused")
    private Integer numberRewardsUnused;

    // Links
    @Column(name = "short_link", length = 500)
    private String shortLink;

    @Column(name = "share_link", length = 500)
    private String shareLink;

    @Column(name = "install_link_universal", length = 500)
    private String installLinkUniversal;

    @Column(name = "install_link_apple", length = 500)
    private String installLinkApple;

    @Column(name = "install_link_google", length = 500)
    private String installLinkGoogle;

    @Column(name = "install_link_pwa", length = 500)
    private String installLinkPwa;

    // Custom fields from Boomerangme (JSON stored as text)
    @Column(name = "custom_fields", columnDefinition = "TEXT")
    private String customFields;

    // Tracking
    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // Relationship timestamps
    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "installed_at")
    private LocalDateTime installedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_reward_redeemed_at")
    private LocalDateTime lastRewardRedeemedAt;

    @Column(name = "last_reward_earned_at")
    private LocalDateTime lastRewardEarnedAt;

    @Column(name = "last_stamp_earned_at")
    private LocalDateTime lastStampEarnedAt;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // One-to-One relationship with Customer
    @OneToOne(mappedBy = "card", cascade = CascadeType.ALL)
    private Customer customer;
}

