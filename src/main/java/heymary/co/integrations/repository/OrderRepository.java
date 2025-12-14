package heymary.co.integrations.repository;

import heymary.co.integrations.model.IntegrationType;
import heymary.co.integrations.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    /**
     * Find an order by merchant ID, external order ID, and integration type
     * An order is unique by merchant_id + external_order_id + integration_type
     */
    Optional<Order> findByMerchantIdAndExternalOrderIdAndIntegrationType(
            String merchantId, String externalOrderId, IntegrationType integrationType);
    
    /**
     * Find the latest order date for a specific merchant
     * Returns the most recent order_date for orders synced for this merchant
     */
    @Query("SELECT MAX(o.orderDate) FROM Order o WHERE o.merchantId = :merchantId")
    Optional<LocalDateTime> findLatestOrderDateByMerchantId(@Param("merchantId") String merchantId);
}

