package heymary.co.integrations.repository;

import heymary.co.integrations.model.RewardTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RewardTierRepository extends JpaRepository<RewardTier, Long> {
    List<RewardTier> findByTemplateId(Integer templateId);
    void deleteByTemplateId(Integer templateId);
}
