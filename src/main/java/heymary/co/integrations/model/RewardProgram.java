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
 * Represents a reward program mechanics configuration for a template.
 * Defines how points are earned (spend, visit, or points mechanics).
 * Used for calculating points from orders.
 */
@Entity
@Table(name = "reward_programs", indexes = {
    @Index(name = "idx_reward_programs_template_id", columnList = "template_id"),
    @Index(name = "idx_reward_programs_mechanics_type", columnList = "mechanics_type")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_reward_programs_template_id", columnNames = {"template_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false, unique = true)
    private Integer templateId; // Boomerangme template ID (one program per template)

    @Column(name = "mechanics_type", nullable = false, length = 50)
    private String mechanicsType; // 'spend', 'visit', or 'points'

    @Column(name = "points_per_unit")
    private Integer pointsPerUnit; // Points per unit (e.g., 10 points per $1 for spend)

    @Column(name = "unit_amount", precision = 10, scale = 2)
    private BigDecimal unitAmount; // Unit amount (e.g., 1.00 for $1)

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
