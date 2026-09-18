package com.testpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.client.LlmClient;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.repository.connector.RepositoryTransport;
import com.testpilot.repository.entity.*;
import com.testpilot.repository.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("h2") @Transactional
class CodeReviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired ConnectedRepositoryRepository repositories;
    @Autowired RepositoryIngestionRepository ingestions;
    @Autowired RepositoryArtifactRepository artifacts;
    @Autowired CodeReviewRepository reviews;
    @Autowired CodeReviewService service;
    @MockBean LlmClient llm;
    private String owner, other;
    private long project, ingestion;
    @BeforeEach void setup() throws Exception {
        owner = "Bearer " + auth.register(new RegisterRequest("Reviewer", "reviewer@test.local", "password")).token();
        other = "Bearer " + auth.register(new RegisterRequest("Other", "other-reviewer@test.local", "password")).token();
        var created = mvc.perform(post("/api/projects").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CreateProjectRequest("Review", "fixture")))).andExpect(status().isCreated()).andReturn();
        project = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        var repo = repositories.saveAndFlush(new ConnectedRepository(project, RepositoryTransport.GITHUB_MCP,
                "owner", "repo", "main", "a".repeat(40), null, "fixture"));
        var intake = new RepositoryIngestion(repo.getId(), "a".repeat(40)); intake.complete(BuildSystem.MIXED, 6, "b".repeat(64));
        ingestion = ingestions.saveAndFlush(intake).getId();
        add("src/App.java", "public class App {}\n");
        add("app.py", "import subprocess\ndef run(command):\n    subprocess.run(command, shell=True)\n");
        add("app.js", "export function copy(xs) { return xs.map(x => ({...x})); }\n");
        add("app.ts", "export const add = (a: number, b: number): number => a + b;\n");
        add("main.go", "package main\n");
        add(".env", "SHOULD_NEVER_REACH_PROVIDER=fixture\n");
        when(llm.providerId()).thenReturn("fixture-provider"); when(llm.modelId()).thenReturn("fixture-review-v1");
        when(llm.generateStructured(anyString(), anyString(), eq(ReviewReport.BatchResponse.class))).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            assertFalse(prompt.contains("SHOULD_NEVER_REACH_PROVIDER"));
            int begin = prompt.indexOf("[{"), end = prompt.lastIndexOf("]");
            var sources = mapper.readTree(prompt.substring(begin, end+1));
            List<String> paths = new ArrayList<>(); sources.forEach(s -> paths.add(s.path("path").asText()));
            List<ReviewReport.Finding> findings = new ArrayList<>();
            if (paths.contains("app.py")) findings.add(new ReviewReport.Finding("IMPROVEMENT", "SECURITY", "HIGH", "Shell execution accepts a command",
                    "If callers supply untrusted command text, the shell can interpret it.", "Use a fixed executable and an argument list with shell=False; test metacharacters as literal arguments.",
                    List.of(new ReviewReport.Evidence("app.py", 3, 3, "    subprocess.run(command, shell=True)"))));
            if (paths.contains("app.ts")) findings.add(new ReviewReport.Finding("GOOD_PRACTICE", "MAINTAINABILITY", "INFO", "Explicit numeric function contract",
                    "Arguments and result are explicitly typed.", "Preserve these types when extending the function and add boundary tests.",
                    List.of(new ReviewReport.Evidence("app.ts", 1, 1, "export const add = (a: number, b: number): number => a + b;"))));
            if (paths.contains("app.js")) findings.add(new ReviewReport.Finding("IMPROVEMENT", "PERFORMANCE", "LOW", "A full object copy is allocated per item",
                    "Large arrays allocate one new object per input item; profiling is needed before changing semantics.", "Measure this call with representative arrays; if immutable references satisfy the contract, avoid copying every object. Test aliasing before changing behavior.",
                    List.of(new ReviewReport.Evidence("app.js", 1, 1, "export function copy(xs) { return xs.map(x => ({...x})); }"))));
            return new ReviewReport.BatchResponse(paths, findings);
        });
    }
    private void add(String path, String content) {
        artifacts.saveAndFlush(new RepositoryArtifact(ingestion, path, "a".repeat(40), ReviewSnapshotService.hash(content),
                RepositoryArtifactKind.SOURCE_CODE, content.length(), content));
    }
    private String base() { return "/api/projects/" + project + "/code-reviews"; }
    private String snapshot() throws Exception { return mapper.readTree(mvc.perform(get(base()+"/plan").header("Authorization", owner))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("snapshotId").asText(); }
    private String start(String snapshot, int offset) throws Exception { return mvc.perform(post(base()).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.createObjectNode().put("snapshotId", snapshot).put("batchOffset", offset).toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }

    @Test void reviewsAllSupportedLanguagesRecognizesGoodPracticesAndSavesFrozenEvidence() throws Exception {
        String result = start(snapshot(), 0);
        var json = mapper.readTree(result);
        long id = json.path("id").asLong();
        assertEquals("PARTIAL", json.path("status").asText());
        assertEquals(3, json.path("report").path("findings").size());
        assertEquals(4, json.path("report").path("files").findValuesAsText("status").stream().filter("REVIEWED"::equals).count());
        add("new.py", "value = 1\n");
        mvc.perform(get(base()+"/"+id+"/download").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment;")))
                .andExpect(content().json(result));
        mvc.perform(get(base()).header("Authorization", owner)).andExpect(jsonPath("$.length()").value(1));
        verify(llm, times(1)).generateStructured(anyString(), anyString(), eq(ReviewReport.BatchResponse.class));
    }
    @Test void authorizationAndStaleSnapshotChecksHappenBeforeProviderInvocation() throws Exception {
        String snapshot = snapshot();
        for (String path : List.of(base(), base()+"/plan", base()+"/123", base()+"/123/download")) {
            mvc.perform(get(path).header("Authorization", other)).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        String body = mapper.createObjectNode().put("snapshotId", snapshot).toString();
        mvc.perform(post(base()).header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        add("changed.py", "value = 1");
        mvc.perform(post(base()).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(base()).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{\"snapshotId\":\"fake\"}")).andExpect(status().isBadRequest());
        verify(llm, never()).generateStructured(anyString(), anyString(), any());
    }
    @Test void mockAndProviderFailuresAreUnavailableNotCleanReviews() throws Exception {
        String snapshot = snapshot();
        when(llm.providerId()).thenReturn("mock");
        var mock = mapper.readTree(start(snapshot, 0));
        assertEquals("UNAVAILABLE", mock.path("status").asText());
        assertEquals(0, mock.path("report").path("findings").size());
        verify(llm, never()).generateStructured(anyString(), anyString(), any());
        when(llm.providerId()).thenReturn("fixture-provider");
        when(llm.generateStructured(anyString(), anyString(), eq(ReviewReport.BatchResponse.class))).thenThrow(new IllegalStateException("private provider diagnostic"));
        String failure = start(snapshot, 0);
        assertFalse(failure.contains("private provider diagnostic"));
        var failed = mapper.readTree(failure);
        assertEquals("UNAVAILABLE", failed.path("status").asText());
        assertEquals(1, failed.path("report").path("failedBatches").asInt());
        assertEquals(2, reviews.count());
    }
    @Test void preservesRejectedFindingCountWithoutReturningFabricatedLocations() throws Exception {
        var invalid = new ReviewReport.Finding("IMPROVEMENT", "SECURITY", "HIGH", "False reference", "explanation", "guidance",
                List.of(new ReviewReport.Evidence("app.py", 999, 999, "invented")));
        when(llm.generateStructured(anyString(), anyString(), eq(ReviewReport.BatchResponse.class)))
                .thenReturn(new ReviewReport.BatchResponse(List.of("app.py"), List.of(invalid)));
        var report = mapper.readTree(start(snapshot(), 0)).path("report");
        assertEquals(1, report.path("rejectedFindings").asInt()); assertEquals(0, report.path("findings").size());
        assertEquals("PARTIAL", report.path("status").asText());
    }
    @Test void continuationReviewsRemainingBatchesWithoutOverwritingEarlierResults() throws Exception {
        for (int i=0; i<18; i++) add("large"+String.format("%02d", i)+".py", "# "+"x".repeat(21000));
        String snapshot = snapshot();
        var first = mapper.readTree(start(snapshot, 0));
        assertEquals(8, first.path("report").path("nextBatchOffset").asInt());
        var second = mapper.readTree(start(snapshot, 8));
        assertNotEquals(first.path("id"), second.path("id"));
        assertEquals(8, second.path("report").path("batchOffset").asInt());
        assertEquals(8, second.path("report").path("successfulBatches").asInt());
        mvc.perform(get(base()+"/"+first.path("id").asLong()).header("Authorization", owner)).andExpect(content().json(first.toString()));
    }
    @Test void validatesCatalogHashesAndDoesNotFallBackToUnrelatedManualFiles() throws Exception {
        artifacts.saveAndFlush(new RepositoryArtifact(ingestion, "tampered.py", "a".repeat(40), "0".repeat(64), RepositoryArtifactKind.SOURCE_CODE, 9, "value = 1"));
        mvc.perform(get(base()+"/plan").header("Authorization", owner)).andExpect(status().isBadRequest());
        verify(llm, never()).generateStructured(anyString(), anyString(), any());
    }
    @Test void interruptedReviewRecoveryDoesNotInventSuccessfulAnalysis() throws Exception {
        var result = mapper.readTree(start(snapshot(), 0));
        var old = new CodeReview(project, result.path("report").path("snapshotId").asText(), result.path("report").toString());
        org.springframework.test.util.ReflectionTestUtils.setField(old, "startedAt", java.time.LocalDateTime.now().minusMinutes(11));
        old = reviews.saveAndFlush(old);
        service.recoverInterrupted();
        mvc.perform(get(base()+"/"+old.getId()).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERRUPTED")).andExpect(jsonPath("$.report.findings.length()").value(0));
    }
    @Test void oversizedFilesAreExplicitlyExcludedWithoutTruncatingThemForTheProvider() throws Exception {
        add("oversized.py", "#" + "x".repeat(30001));
        var result = mapper.readTree(start(snapshot(), 0)).path("report");
        var file = java.util.stream.StreamSupport.stream(result.path("files").spliterator(), false)
                .filter(f -> f.path("path").asText().equals("oversized.py")).findFirst().orElseThrow();
        assertEquals("EXCLUDED", file.path("status").asText());
        verify(llm).generateStructured(argThat(p -> !p.contains("oversized.py")), anyString(), eq(ReviewReport.BatchResponse.class));
    }
    @Test void absentReviewAndInvalidOffsetsDoNotLaunchAProviderCall() throws Exception {
        mvc.perform(get(base()+"/999999").header("Authorization", owner)).andExpect(status().isNotFound());
        for (int offset : List.of(-1, 999)) mvc.perform(post(base()).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("snapshotId", snapshot()).put("batchOffset", offset).toString())).andExpect(status().isBadRequest());
        verify(llm, never()).generateStructured(anyString(), anyString(), any());
    }

}
