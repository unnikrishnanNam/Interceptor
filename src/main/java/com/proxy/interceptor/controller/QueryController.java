package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApprovalRequest;
import com.proxy.interceptor.dto.VoteRequest;
import com.proxy.interceptor.model.BlockedQuery;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.BlockedQueryService;
import com.proxy.interceptor.service.ReplayProtectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueryController {

    private final BlockedQueryService blockedQueryService;
    private final AuditService auditService;
    private final ReplayProtectionService replayProtectionService;

    @GetMapping("/blocked")
    public ResponseEntity<List<BlockedQuery>> getBlockedQueries() {
        return ResponseEntity.ok(blockedQueryService.getPendingQueries());
    }

    @GetMapping("/blocked/all")
    public ResponseEntity<List<BlockedQuery>> getAllQueries() {
        return ResponseEntity.ok(blockedQueryService.getAllQueries());
    }

    @GetMapping("/blocked/{id}/votes")
    public ResponseEntity<?> getVoteStatus(@PathVariable Long id) {
        var status = blockedQueryService.getVoteStatus(id);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @PostMapping("/approve")
    public ResponseEntity<?> approveQuery(
            @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest httpRequest
    ) {
        String username = (String) httpRequest.getAttribute("username");
        String clientIp = getClientIp(httpRequest);

        // Replay protection
        if (request.nonce() != null && request.timestamp() != null) {
            if (!replayProtectionService.validateRequest(
                    request.nonce(), request.timestamp(),
                    "approve:" + request.id(), username
            )) {
                auditService.log(username, "replay_attack_blocked",
                        "Attempted replay on approve for query #" + request.id(), clientIp);
                return ResponseEntity.status(403).body(Map.of("error", "Replay attack detected"));
            }
        }

        boolean ok = blockedQueryService.approveQuery(request.id(), username);

        if (ok) {
            auditService.log(username, "query_approved",
                    "Query #" + request.id() + "approved", clientIp);
        }

        return ResponseEntity.ok(Map.of("success", ok));
    }

    @PostMapping("/reject")
    public ResponseEntity<?> rejectQuery(
            @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest httpRequest
    ) {
        String username = (String) httpRequest.getAttribute("username");
        String clientIp = getClientIp(httpRequest);

        // Replay protection
        if (request.nonce() != null && request.timestamp() != null) {
            if (!replayProtectionService.validateRequest(
                    request.nonce(), request.timestamp(),
                    "approve:" + request.id(), username
            )) {
                auditService.log(username, "replay_attack_blocked",
                        "Attempted replay on approve for query #" + request.id(), clientIp);
                return ResponseEntity.status(403).body(Map.of("error", "Replay attack detected"));
            }
        }

        boolean ok = blockedQueryService.rejectQuery(request.id(), username);

        if (ok) {
            auditService.log(username, "query_rejected",
                    "Query #" + request.id() + "rejected", clientIp);
        }

        return ResponseEntity.ok(Map.of("success", ok));
    }

    @PostMapping("/vote")
    public ResponseEntity<?> vote(
            @Valid @RequestBody VoteRequest request,
            HttpServletRequest httpRequest
    ) {
        String username = (String) httpRequest.getAttribute("username");
        String clientIp = getClientIp(httpRequest);

        // Replay protection
        if (request.nonce() != null && request.timestamp() != null) {
            if (!replayProtectionService.validateRequest(
                    request.nonce(), request.timestamp(),
                    "vote:" + request.id() + ":" + request.vote(), username)) {
                return ResponseEntity.status(403).body(Map.of("error", "Replay attack detected"));
            }
        }

        Map<String, Object> result = blockedQueryService.addVote(request.id(), username, request.vote());

        // Check for duplicates and return 403 error if detected
        if (Boolean.TRUE.equals(result.get("duplicate"))) return ResponseEntity.status(403).body(result);

        auditService.log(username, "query_vote",
            String.format("Vote %s on query #%d", request.vote(), request.id()), clientIp);

        return ResponseEntity.ok(result);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
