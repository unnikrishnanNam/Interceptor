package com.proxy.interceptor.service;

import com.proxy.interceptor.dto.LoginResult;
import com.proxy.interceptor.model.Role;
import com.proxy.interceptor.model.User;
import com.proxy.interceptor.repository.UserRepository;
import com.proxy.interceptor.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResult login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            log.warn("Login failed: users {} not found", username);
            return new LoginResult(false, null, null, "Invalid credentials");
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Login failed: invalid password for user {}", username);
            return new LoginResult(false, null, null, "Invalid credentials");
        }

        // Update last login
        user.setLastLogin(Instant.now());
        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(username, user.getRole().name());
        log.info("User {} logged in successfully", username);

        return new LoginResult(true, token, user, null);
    }

    public User createUser(String username, String password, Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .build();

        return userRepository.save(user);
    }

    public void createAdminIfNotExists(String username, String password) {
        if (!userRepository.existsByUsername(username)) {
            createUser(username, password, Role.ADMIN);
            log.info("Created default admin user: {}", username);
        }
    }
}
