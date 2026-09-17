package com.testpilot.testing.execution.sandbox;

import com.testpilot.auth.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/test-drafts/{draftId}/execution")
public class DraftExecutionController {
    private final DraftExecutionService service;
    public DraftExecutionController(DraftExecutionService service) { this.service = service; }
    @GetMapping public DraftExecutionService.Response get(@PathVariable Long projectId, @PathVariable Long draftId,
            @AuthenticationPrincipal UserPrincipal user) { return service.get(projectId, draftId, user); }
    @PostMapping public DraftExecutionService.Response execute(@PathVariable Long projectId, @PathVariable Long draftId,
            @AuthenticationPrincipal UserPrincipal user) { return service.execute(projectId, draftId, user); }
}
