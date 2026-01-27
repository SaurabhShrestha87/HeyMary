package heymary.co.integrations.repository;

import heymary.co.integrations.model.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Integer> {
    Optional<Template> findByTemplateId(Integer templateId);
    Optional<Template> findByMerchantIdAndTemplateId(String merchantId, Integer templateId);
    boolean existsByTemplateId(Integer templateId);
}
