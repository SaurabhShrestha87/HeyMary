package heymary.co.integrations.controller;

import heymary.co.integrations.model.Customer;
import heymary.co.integrations.model.IntegrationType;
import heymary.co.integrations.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/treez/integrations")
@RequiredArgsConstructor
public class TreezIntegrationController {

    private final CustomerRepository customerRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTreezIntegrations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String search) {

        // Fetch all customers and filter for Treez
        List<Customer> allCustomers = customerRepository.findAll();
        
        List<Customer> treezCustomers = allCustomers.stream()
                .filter(c -> c.getIntegrationType() == IntegrationType.TREEZ)
                .filter(c -> {
                    if (merchantId != null && !merchantId.isEmpty()) {
                        if (!c.getMerchantId().equals(merchantId)) return false;
                    }
                    if (search != null && !search.isEmpty()) {
                        String searchLower = search.toLowerCase();
                        return (c.getTreezEmail() != null && c.getTreezEmail().toLowerCase().contains(searchLower)) ||
                               (c.getTreezPhone() != null && c.getTreezPhone().toLowerCase().contains(searchLower)) ||
                               (c.getExternalCustomerId() != null && c.getExternalCustomerId().toLowerCase().contains(searchLower)) ||
                               (c.getCard() != null && c.getCard().getCardholderEmail() != null && 
                                c.getCard().getCardholderEmail().toLowerCase().contains(searchLower)) ||
                               (c.getCard() != null && c.getCard().getCardholderPhone() != null && 
                                c.getCard().getCardholderPhone().toLowerCase().contains(searchLower));
                    }
                    return true;
                })
                .sorted((a, b) -> {
                    if (a.getUpdatedAt() == null && b.getUpdatedAt() == null) return 0;
                    if (a.getUpdatedAt() == null) return 1;
                    if (b.getUpdatedAt() == null) return -1;
                    return b.getUpdatedAt().compareTo(a.getUpdatedAt());
                })
                .collect(Collectors.toList());
        
        // Apply pagination manually
        int start = page * size;
        int end = Math.min(start + size, treezCustomers.size());
        List<Customer> paginatedCustomers = start < treezCustomers.size() 
                ? treezCustomers.subList(start, end) 
                : new java.util.ArrayList<>();

        List<Map<String, Object>> integrations = paginatedCustomers.stream()
                .map(this::mapToIntegrationDto)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("integrations", integrations);
        response.put("currentPage", page);
        response.put("totalItems", treezCustomers.size());
        response.put("totalPages", (int) Math.ceil((double) treezCustomers.size() / size));
        response.put("pageSize", size);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<Customer> allCustomers = customerRepository.findAll();
        
        long totalTreez = allCustomers.stream()
                .filter(c -> c.getIntegrationType() == IntegrationType.TREEZ)
                .count();
        
        long withCards = allCustomers.stream()
                .filter(c -> c.getIntegrationType() == IntegrationType.TREEZ)
                .filter(c -> c.getCard() != null)
                .count();
        
        long withoutCards = totalTreez - withCards;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTreezCustomers", totalTreez);
        stats.put("withCards", withCards);
        stats.put("withoutCards", withoutCards);
        stats.put("linkageRate", totalTreez > 0 ? (double) withCards / totalTreez * 100 : 0);

        return ResponseEntity.ok(stats);
    }

    private Map<String, Object> mapToIntegrationDto(Customer customer) {
        Map<String, Object> dto = new HashMap<>();
        
        // Customer Info
        dto.put("customerId", customer.getId());
        dto.put("merchantId", customer.getMerchantId());
        dto.put("externalCustomerId", customer.getExternalCustomerId());
        
        // Treez Data
        Map<String, Object> treezData = new HashMap<>();
        treezData.put("email", customer.getTreezEmail());
        treezData.put("phone", customer.getTreezPhone());
        treezData.put("firstName", customer.getTreezFirstName());
        treezData.put("lastName", customer.getTreezLastName());
        treezData.put("birthDate", customer.getTreezBirthDate());
        dto.put("treez", treezData);
        
        // Points
        dto.put("totalPoints", customer.getTotalPoints());
        
        // Card Info (Boomerangme)
        if (customer.getCard() != null) {
            Map<String, Object> cardData = new HashMap<>();
            cardData.put("cardId", customer.getCard().getId());
            cardData.put("cardholderId", customer.getCard().getCardholderId());
            cardData.put("serialNumber", customer.getCard().getSerialNumber());
            cardData.put("status", customer.getCard().getStatus());
            cardData.put("email", customer.getCard().getCardholderEmail());
            cardData.put("phone", customer.getCard().getCardholderPhone());
            cardData.put("firstName", customer.getCard().getCardholderFirstName());
            cardData.put("lastName", customer.getCard().getCardholderLastName());
            cardData.put("bonusBalance", customer.getCard().getBonusBalance());
            cardData.put("balance", customer.getCard().getBalance());
            cardData.put("countVisits", customer.getCard().getCountVisits());
            cardData.put("numberStampsTotal", customer.getCard().getNumberStampsTotal());
            cardData.put("numberRewardsUnused", customer.getCard().getNumberRewardsUnused());
            cardData.put("issuedAt", customer.getCard().getIssuedAt());
            cardData.put("installedAt", customer.getCard().getInstalledAt());
            dto.put("boomerangme", cardData);
        } else {
            dto.put("boomerangme", null);
        }
        
        // Timestamps
        dto.put("createdAt", customer.getCreatedAt());
        dto.put("updatedAt", customer.getUpdatedAt());
        dto.put("syncedAt", customer.getSyncedAt());
        
        return dto;
    }
}

