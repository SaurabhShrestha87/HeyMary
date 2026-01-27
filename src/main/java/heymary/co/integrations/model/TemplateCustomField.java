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
 * Represents a custom field defined in a Boomerangme template.
 * Custom fields are modular and can be configured per template.
 */
@Entity
@Table(name = "template_custom_fields", indexes = {
    @Index(name = "idx_template_custom_fields_template_id", columnList = "template_id"),
    @Index(name = "idx_template_custom_fields_field_id", columnList = "field_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_template_custom_fields_template_field", columnNames = {"template_id", "field_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateCustomField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Integer templateId; // Boomerangme template ID

    @Column(name = "field_id", nullable = false)
    private Integer fieldId; // Boomerangme custom field ID

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", nullable = false, length = 50)
    private String type; // e.g., "FName", "SName", "phone", "email", "DateOfBirth"

    @Column(name = "\"order\"")  // Quote reserved keyword
    @Builder.Default
    private Integer order = 0;

    @Column(name = "required")
    @Builder.Default
    private Boolean required = false;

    @Column(name = "unique_field")
    @Builder.Default
    private Boolean uniqueField = false;

    @Column(name = "value", columnDefinition = "TEXT")
    private String value; // Current value (null for template definition)

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
