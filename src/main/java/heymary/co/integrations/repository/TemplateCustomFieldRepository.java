package heymary.co.integrations.repository;

import heymary.co.integrations.model.TemplateCustomField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateCustomFieldRepository extends JpaRepository<TemplateCustomField, Long> {
    List<TemplateCustomField> findByTemplateId(Integer templateId);
    void deleteByTemplateId(Integer templateId);
}
