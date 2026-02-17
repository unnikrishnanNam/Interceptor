package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.CreateUserRequest;
import com.proxy.interceptor.dto.UserResponse;
import com.proxy.interceptor.model.Role;
import com.proxy.interceptor.model.User;
import com.proxy.interceptor.repository.UserRepository;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
import com.proxy.interceptor.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PostMapping
    public ResponseEntity<?> createUser(
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest
    ) {
        try {
            Role role = Role.valueOf(request.role().toUpperCase());
            User user = authService.createUser(request.username(), request.password(), role);

            String adminUsername = (String) httpRequest.getAttribute("username");
            auditService.log(adminUsername, "user_created",
                    "Created user: " + request.username() + " with role: " + role,
                    getClientIp(httpRequest));

            user.setPasswordHash(null);
            return ResponseEntity.ok(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role does not exist"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        var userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        String adminUsername = (String) httpRequest.getAttribute("username");

        // Prevent self-deletion
        if (user.getUsername().equals(adminUsername)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete yourself"));
        }

        userRepository.delete(user);

        auditService.log(adminUsername, "user_deleted",
                "Deleted user: " + user.getUsername(),
                getClientIp(httpRequest));

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
