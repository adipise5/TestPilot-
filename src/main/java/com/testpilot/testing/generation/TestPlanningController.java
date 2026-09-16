package com.testpilot.testing.generation;

import com.testpilot.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class TestPlanningController {
    private final TestPlanningService service;
    public TestPlanningController(TestPlanningService service) { this.service = service; }
    public record GenerateRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotId,
                                  @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String planId) {}
    @GetMapping("/test-plan")
    public TestPlanningService.PlanResponse plan(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal user) {
        return service.plan(projectId, user);
    }
    @PostMapping("/test-drafts")
    public TestPlanningService.SavedDraft generate(@PathVariable Long projectId, @Valid @RequestBody GenerateRequest request,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        return service.generate(projectId, request.snapshotId(), request.planId(), user);
    }
    @GetMapping("/test-drafts")
    public List<TestPlanningService.SavedDraft> drafts(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal user) {
        return service.drafts(projectId, user);
    }
}
