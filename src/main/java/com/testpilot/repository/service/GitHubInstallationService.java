package com.testpilot.repository.service;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.common.exception.InvalidRequestException;
import com.testpilot.repository.dto.GitHubInstallationCallbackResponse;
import com.testpilot.repository.dto.GitHubInstallationStartResponse;
import com.testpilot.repository.entity.GitHubInstallationState;
import com.testpilot.repository.github.GitHubAppTokenProvider;
import com.testpilot.repository.repository.GitHubInstallationGrantRepository;
import com.testpilot.repository.repository.GitHubInstallationStateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class GitHubInstallationService {

    private static final int STATE_TTL_SECONDS = 600;

    private final GitHubInstallationGrantRepository grantRepository;
    private final GitHubInstallationStateRepository stateRepository;
    private final GitHubAppTokenProvider tokenProvider;
    private final GitHubInstallationPersistenceService persistenceService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String appSlug;

    public GitHubInstallationService(
            GitHubInstallationGrantRepository grantRepository,
            GitHubInstallationStateRepository stateRepository,
            GitHubAppTokenProvider tokenProvider,
            GitHubInstallationPersistenceService persistenceService,
            @Value("${testpilot.github.app-slug:}") String appSlug) {
        this.grantRepository = grantRepository;
        this.stateRepository = stateRepository;
        this.tokenProvider = tokenProvider;
        this.persistenceService = persistenceService;
        this.appSlug = appSlug;
    }

    @Transactional
    public GitHubInstallationStartResponse start(UserPrincipal currentUser) {
        if (appSlug.isBlank()) {
            throw new InvalidRequestException("GitHub App installation is not configured");
        }
        byte[] stateBytes = new byte[32];
        secureRandom.nextBytes(stateBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
        stateRepository.save(new GitHubInstallationState(
                hash(state),
                currentUser.getId(),
                LocalDateTime.now().plusSeconds(STATE_TTL_SECONDS)));
        String url = "https://github.com/apps/" + appSlug + "/installations/new?state=" + state;
        return new GitHubInstallationStartResponse(url, STATE_TTL_SECONDS);
    }

    public GitHubInstallationCallbackResponse complete(String state, Long installationId, String setupAction) {
        if (state == null || state.isBlank() || installationId == null || installationId <= 0) {
            throw new InvalidRequestException("GitHub installation callback is invalid");
        }
        if (setupAction != null && !setupAction.equals("install") && !setupAction.equals("update")) {
            throw new InvalidRequestException("GitHub installation action is unsupported");
        }
        tokenProvider.getInstallationToken(installationId, Optional.empty());
        persistenceService.consumeState(hash(state), installationId);
        return new GitHubInstallationCallbackResponse(installationId, "CONNECTED");
    }

    @Transactional(readOnly = true)
    public void requireGrant(Long userId, Long installationId) {
        if (userId == null || installationId == null
                || !grantRepository.existsByUserIdAndInstallationIdAndActiveTrue(userId, installationId)) {
            throw new AccessDeniedException("GitHub installation is not linked to the current account");
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
