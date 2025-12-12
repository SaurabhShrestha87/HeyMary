package heymary.co.integrations.repository;

import heymary.co.integrations.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByMerchantIdAndDutchieCustomerId(String merchantId, String dutchieCustomerId);
    Optional<Customer> findByMerchantIdAndBoomerangmeCardId(String merchantId, String boomerangmeCardId);
    Optional<Customer> findByMerchantIdAndEmail(String merchantId, String email);
}

