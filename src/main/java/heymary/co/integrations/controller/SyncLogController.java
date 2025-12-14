package heymary.co.integrations.controller;

import heymary.co.integrations.model.SyncLog;
import heymary.co.integrations.repository.SyncLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync-logs")
@RequiredArgsConstructor
public class SyncLogController {

    private final SyncLogRepository syncLogRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSyncLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) SyncLog.SyncStatus status,
            @RequestParam(required = false) SyncLog.SyncType syncType,
            @RequestParam(required = false) SyncLog.EntityType entityType,
            @RequestParam(required = false) SyncLog.SystemType sourceSystem,
            @RequestParam(required = false) SyncLog.SystemType targetSystem,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        // For now, we'll fetch all and filter in memory
        // In production, you'd want to use JPA Specifications or QueryDSL for better performance
        Page<SyncLog> allLogs = syncLogRepository.findAll(pageable);
        
        List<SyncLog> filteredLogs = allLogs.getContent().stream()
                .filter(log -> merchantId == null || log.getMerchantId().equals(merchantId))
                .filter(log -> status == null || log.getStatus() == status)
                .filter(log -> syncType == null || log.getSyncType() == syncType)
                .filter(log -> entityType == null || log.getEntityType() == entityType)
                .filter(log -> sourceSystem == null || log.getSourceSystem() == sourceSystem)
                .filter(log -> targetSystem == null || log.getTargetSystem() == targetSystem)
                .filter(log -> startDate == null || log.getCreatedAt().isAfter(startDate) || log.getCreatedAt().isEqual(startDate))
                .filter(log -> endDate == null || log.getCreatedAt().isBefore(endDate) || log.getCreatedAt().isEqual(endDate))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("logs", filteredLogs);
        response.put("currentPage", page);
        response.put("totalItems", filteredLogs.size());
        response.put("totalPages", (int) Math.ceil((double) filteredLogs.size() / size));
        response.put("pageSize", size);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<SyncLog> allLogs = syncLogRepository.findAll();
        
        long total = allLogs.size();
        long success = allLogs.stream().filter(log -> log.getStatus() == SyncLog.SyncStatus.SUCCESS).count();
        long failed = allLogs.stream().filter(log -> log.getStatus() == SyncLog.SyncStatus.FAILED).count();
        long retrying = allLogs.stream().filter(log -> log.getStatus() == SyncLog.SyncStatus.RETRYING).count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("retrying", retrying);
        stats.put("successRate", total > 0 ? (double) success / total * 100 : 0);

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SyncLog> getSyncLogById(@PathVariable Long id) {
        return syncLogRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

