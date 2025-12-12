package heymary.co.integrations.repository;

import heymary.co.integrations.model.SyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SyncLogRepository extends JpaRepository<SyncLog, Long> {
    List<SyncLog> findByMerchantIdAndStatus(String merchantId, SyncLog.SyncStatus status);
    
    @Query("SELECT s FROM SyncLog s WHERE s.status = 'FAILED' AND s.createdAt >= :since ORDER BY s.createdAt DESC")
    List<SyncLog> findRecentFailures(LocalDateTime since);
}

