package com.testpilot.auth.service;

import com.testpilot.auth.dto.AuthResponse;
import com.testpilot.auth.dto.CreateManagedUserRequest;
import com.testpilot.auth.dto.LoginRequest;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.dto.UserResponse;
import com.testpilot.auth.entity.Role;
import com.testpilot.auth.entity.User;
import com.testpilot.auth.repository.UserRepository;
import com.testpilot.auth.security.JwtTokenProvider;
import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.DuplicateEmailException;
import com.testpilot.common.exception.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User savedUser = createUser(request.name(), request.email(), request.password(), Role.DEVELOPER);
        UserPrincipal principal = UserPrincipal.create(savedUser);
        String token = tokenProvider.generateToken(principal);

        return new AuthResponse(
                token,
                savedUser.getId(),
                savedUser.getName(),
                savedUser.getEmail(),
                savedUser.getRole()
        );
    }

    @Transactional
    public UserResponse createManagedUser(CreateManagedUserRequest request) {
        User savedUser = createUser(request.name(), request.email(), request.password(), request.role());
        return UserResponse.fromEntity(savedUser);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        UserPrincipal principal = UserPrincipal.create(user);
        String token = tokenProvider.generateToken(principal);

        return new AuthResponse(
                token,
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole()
        );
    }

    private User createUser(String name, String email, String password, Role role) {
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException("Email is already registered: " + email);
        }

        return userRepository.save(new User(name, email, passwordEncoder.encode(password), role));
    }
}
