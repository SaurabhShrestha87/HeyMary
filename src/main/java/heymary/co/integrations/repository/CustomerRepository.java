package heymary.co.integrations.repository;

import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    
    // Primary method for finding customers by external POS ID
    Optional<Customer> findByMerchantIdAndExternalCustomerIdAndIntegrationType(
        String merchantId, String externalCustomerId, IntegrationType integrationType);
    
    Optional<Customer> findByCardId(Long cardId);
    
    // Treez-specific lookup methods (for matching with Boomerangme cards)
    Optional<Customer> findByMerchantIdAndTreezEmailAndIntegrationType(
        String merchantId, String treezEmail, IntegrationType integrationType);
    
    Optional<Customer> findByMerchantIdAndTreezPhoneAndIntegrationType(
        String merchantId, String treezPhone, IntegrationType integrationType);
    
    // Find all customers by merchant and integration type
    List<Customer> findByMerchantIdAndIntegrationType(
        String merchantId, IntegrationType integrationType);
    
    // Legacy methods (kept for backward compatibility - can be removed after migration)
    @Deprecated
    Optional<Customer> findByMerchantIdAndEmail(String merchantId, String email);
    
    @Deprecated
    Optional<Customer> findByMerchantIdAndBoomerangmeCardId(String merchantId, String boomerangmeCardId);
    
    /**
     * Find customer by match field (email or phone) based on match type
     * This is a convenience method that uses the appropriate field based on match type
     */
    @Query("SELECT c FROM Customer c WHERE c.merchantId = :merchantId " +
           "AND c.integrationType = :integrationType " +
           "AND (:matchType = 'EMAIL' AND c.treezEmail = :matchValue) OR " +
           "(:matchType = 'PHONE' AND c.treezPhone = :matchValue)")
    Optional<Customer> findByMerchantIdAndMatchField(
        @Param("merchantId") String merchantId,
        @Param("integrationType") IntegrationType integrationType,
        @Param("matchType") String matchType,
        @Param("matchValue") String matchValue);
}

