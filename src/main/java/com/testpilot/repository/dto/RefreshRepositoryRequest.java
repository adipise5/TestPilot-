package com.testpilot.repository.dto;

import jakarta.validation.constraints.Size;

public record RefreshRepositoryRequest(@Size(max = 255) String revision, Boolean force) {
    public RefreshRepositoryRequest(String revision) { this(revision, false); }
}
