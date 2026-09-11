package com.testpilot.repository.service;

import com.testpilot.repository.entity.GitHubInstallationGrant;
import com.testpilot.repository.entity.GitHubInstallationState;
import com.testpilot.repository.repository.GitHubInstallationGrantRepository;
import com.testpilot.repository.repository.GitHubInstallationStateRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitHubInstallationPersistenceService {

    private final GitHubInstallationGrantRepository grantRepository;
    private final GitHubInstallationStateRepository stateRepository;

    public GitHubInstallationPersistenceService(
            GitHubInstallationGrantRepository grantRepository,
            GitHubInstallationStateRepository stateRepository) {
        this.grantRepository = grantRepository;
        this.stateRepository = stateRepository;
    }

    @Transactional
    public GitHubInstallationGrant consumeState(String stateHash, Long installationId) {
        GitHubInstallationState pending = stateRepository.findByStateHash(stateHash)
                .orElseThrow(() -> new AccessDeniedException("GitHub installation state is invalid or expired"));
        if (!pending.canConsume()) {
            throw new AccessDeniedException("GitHub installation state is invalid or expired");
        }

        GitHubInstallationGrant grant = grantRepository.findByInstallationId(installationId)
                .map(existing -> {
                    if (!existing.getUserId().equals(pending.getUserId())) {
                        throw new AccessDeniedException("GitHub installation is already linked to another account");
                    }
                    existing.activate();
                    return existing;
                })
                .orElseGet(() -> new GitHubInstallationGrant(pending.getUserId(), installationId));
        pending.consume();
        stateRepository.save(pending);
        return grantRepository.save(grant);
    }
}
