package heymary.co.integrations.repository;

import heymary.co.integrations.model.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CardRepository extends JpaRepository<Card, Long> {

    /**
     * Find a card by its serial number
     */
    Optional<Card> findBySerialNumber(String serialNumber);

    /**
     * Find a card by cardholder ID
     */
    Optional<Card> findByCardholderId(String cardholderId);

    /**
     * Find a card by merchant ID and serial number
     */
    Optional<Card> findByMerchantIdAndSerialNumber(String merchantId, String serialNumber);

    /**
     * Find a card by merchant ID and cardholder ID
     */
    Optional<Card> findByMerchantIdAndCardholderId(String merchantId, String cardholderId);

    /**
     * Check if a card exists by serial number
     */
    boolean existsBySerialNumber(String serialNumber);

    /**
     * Check if a card exists by cardholder ID
     */
    boolean existsByCardholderId(String cardholderId);

    /**
     * Find a card by cardholder email
     */
    Optional<Card> findByCardholderEmail(String email);

    /**
     * Find a card by cardholder phone
     */
    Optional<Card> findByCardholderPhone(String phone);

    /**
     * Find a card by merchant ID and cardholder email
     */
    Optional<Card> findByMerchantIdAndCardholderEmail(String merchantId, String email);
}

