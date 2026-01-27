package heymary.co.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Represents a Boomerangme loyalty card template.
 * Templates define the structure and rewards for loyalty cards.
 */
@Entity
@Table(name = "templates", indexes = {
    @Index(name = "idx_templates_merchant_id", columnList = "merchant_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Template {

    @Id
    @Column(name = "template_id")
    private Integer templateId; // Boomerangme template ID (primary key)

    @Column(name = "merchant_id", nullable = false, length = 255)
    private String merchantId;

    @Column(name = "company_id")
    private Integer companyId; // From Boomerangme API

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", length = 50)
    private String type; // e.g., "reward", "stamp", "discount"

    @Column(name = "install_link", length = 500)
    private String installLink;

    @Column(name = "qr_link", length = 500)
    private String qrLink;

    @Column(name = "created_at")
    private LocalDateTime createdAt; // From Boomerangme API

    @Column(name = "updated_at")
    private LocalDateTime updatedAt; // From Boomerangme API

    @Column(name = "synced_at")
    @Builder.Default
    private LocalDateTime syncedAt = LocalDateTime.now(); // Last sync time

    @CreationTimestamp
    @Column(name = "created_at_local", nullable = false, updatable = false)
    private LocalDateTime createdAtLocal;

    @UpdateTimestamp
    @Column(name = "updated_at_local", nullable = false)
    private LocalDateTime updatedAtLocal;
}
