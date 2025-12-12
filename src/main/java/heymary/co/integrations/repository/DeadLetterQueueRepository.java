package heymary.co.integrations.repository;

import heymary.co.integrations.model.DeadLetterQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeadLetterQueueRepository extends JpaRepository<DeadLetterQueue, Long> {
    List<DeadLetterQueue> findByResolvedFalse();
    List<DeadLetterQueue> findByMerchantIdAndResolvedFalse(String merchantId);
    Optional<DeadLetterQueue> findByEntityTypeAndEntityIdAndResolvedFalse(
        DeadLetterQueue.EntityType entityType, 
        String entityId
    );
}

