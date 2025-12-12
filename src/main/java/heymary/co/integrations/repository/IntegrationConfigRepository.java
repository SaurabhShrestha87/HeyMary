package heymary.co.integrations.repository;

import heymary.co.integrations.model.IntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Long> {
    Optional<IntegrationConfig> findByMerchantId(String merchantId);
    Optional<IntegrationConfig> findByMerchantIdAndEnabledTrue(String merchantId);
    Optional<IntegrationConfig> findByIdAndEnabledTrue(Long id);
    List<IntegrationConfig> findAllByEnabledTrue();
}

