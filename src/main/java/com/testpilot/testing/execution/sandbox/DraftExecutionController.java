package com.testpilot.testing.execution.sandbox;

import com.testpilot.auth.security.UserPrincipal;
import com.testpilot.testing.report.TestReport;
import org.springframework.http.*;
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
    @GetMapping("/report") public ResponseEntity<TestReport> report(@PathVariable Long projectId, @PathVariable Long draftId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(projectId, draftId, user));
    }
    @GetMapping("/report/download") public ResponseEntity<TestReport> download(@PathVariable Long projectId, @PathVariable Long draftId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("testpilot-project-" + projectId + "-draft-" + draftId + ".json").build().toString())
                .body(service.report(projectId, draftId, user));
    }
}
