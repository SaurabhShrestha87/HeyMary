package heymary.co.integrations.repository;

import heymary.co.integrations.model.RewardProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RewardProgramRepository extends JpaRepository<RewardProgram, Long> {
    Optional<RewardProgram> findByTemplateId(Integer templateId);
    void deleteByTemplateId(Integer templateId);
}
