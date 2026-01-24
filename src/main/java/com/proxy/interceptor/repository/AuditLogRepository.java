package com.proxy.interceptor.repository;

import com.proxy.interceptor.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop100ByOrderByTimestampDesc();

    List<AuditLog> findByUsernameOrderByTimestampDesc(String username);

    // For replay protection
    Optional<AuditLog> findByRequestHash(String requestHash);

    void deleteByTimestampBefore(Instant cutoffTime);
}
