package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.LoginRequest;
import com.proxy.interceptor.dto.LoginResult;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpServletRequest) {
        LoginResult result = authService.login(request.username(), request.password());

        if (result.success()) {
            auditService.log(request.username(), "login", "Login successful",
                    getClientIp(httpServletRequest));

            return ResponseEntity.ok(Map.of(
                    "token", result.token(),
                    "user", Map.of(
                            "id", result.user().getId(),
                            "username", result.user().getUsername(),
                            "role", result.user().getRole().name()
                    )
            ));
        }

        return ResponseEntity.status(401).body(Map.of("error", result.error()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        // JWT is stateless, so logout is handled client-side
        // Audit logout
        String username = (String) request.getAttribute("username");
        if (username != null) {
            auditService.log(username, "logout", "User logged out", getClientIp(request));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteUser();
    }
}
