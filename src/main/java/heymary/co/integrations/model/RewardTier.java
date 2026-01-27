package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a reward tier defined in a Boomerangme template.
 * Reward tiers define the rewards customers can earn at different point thresholds.
 * These will be synced to POS systems (Treez/Dutchie) in the future.
 */
@Entity
@Table(name = "reward_tiers", indexes = {
    @Index(name = "idx_reward_tiers_template_id", columnList = "template_id"),
    @Index(name = "idx_reward_tiers_tier_id", columnList = "tier_id"),
    @Index(name = "idx_reward_tiers_pos_synced", columnList = "pos_synced, pos_synced_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_reward_tiers_template_tier", columnNames = {"template_id", "tier_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Integer templateId; // Boomerangme template ID

    @Column(name = "tier_id", nullable = false)
    private Integer tierId; // Boomerangme reward tier ID

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", nullable = false)
    private Integer type; // 0 = gift, 1 = cashback, 2 = discount percentage

    @Column(name = "threshold", nullable = false)
    private Integer threshold; // Points required to unlock this tier

    @Column(name = "value", nullable = false, precision = 10, scale = 2)
    private BigDecimal value; // Reward value (amount or percentage)

    @Column(name = "value_limit")
    @Builder.Default
    private Integer valueLimit = 0; // Maximum value limit (0 = unlimited)

    @Column(name = "usage_limit")
    @Builder.Default
    private Integer usageLimit = 0; // Maximum usage limit (0 = unlimited)

    // Future POS sync fields (reserved for later implementation)
    @Column(name = "pos_discount_id", length = 100)
    private String posDiscountId; // POS discount/coupon ID (for syncing to Treez/Dutchie)

    @Column(name = "pos_synced")
    @Builder.Default
    private Boolean posSynced = false; // Whether this tier has been synced to POS

    @Column(name = "pos_synced_at")
    private LocalDateTime posSyncedAt; // Last sync time to POS

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
