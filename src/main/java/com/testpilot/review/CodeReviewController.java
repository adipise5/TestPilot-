package com.testpilot.review;

import com.testpilot.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/code-reviews")
public class CodeReviewController {
    private final CodeReviewService service;
    public CodeReviewController(CodeReviewService service) { this.service = service; }
    public record Request(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotId, @Min(0) int batchOffset) {}
    @GetMapping("/plan") public CodeReviewService.Plan plan(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal user) { return service.plan(projectId, user); }
    @GetMapping public java.util.List<CodeReviewService.Summary> list(@PathVariable Long projectId, @AuthenticationPrincipal UserPrincipal user) { return service.list(projectId, user); }
    @PostMapping public CodeReviewService.Response start(@PathVariable Long projectId, @Valid @RequestBody Request request,
            @AuthenticationPrincipal UserPrincipal user) { return service.start(projectId, request.snapshotId(), request.batchOffset(), user); }
    @GetMapping("/{id}") public ResponseEntity<CodeReviewService.Response> get(@PathVariable Long projectId, @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(projectId, id, user)); }
    @GetMapping("/{id}/download") public ResponseEntity<CodeReviewService.Response> download(@PathVariable Long projectId, @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("testpilot-review-" + id + ".json").build().toString())
                .body(service.get(projectId, id, user));
    }
}
