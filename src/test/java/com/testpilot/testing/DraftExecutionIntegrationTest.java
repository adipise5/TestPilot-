package com.testpilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.auth.dto.RegisterRequest;
import com.testpilot.auth.service.AuthService;
import com.testpilot.project.dto.CreateProjectRequest;
import com.testpilot.project.dto.CreateCodeFileRequest;
import com.testpilot.testing.execution.sandbox.ContainerProcess;
import com.testpilot.testing.generation.TestDraft;
import com.testpilot.testing.generation.TestDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("h2") @Transactional
class DraftExecutionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired TestDraftRepository drafts;
    @MockBean ContainerProcess process;
    private String owner, other;
    private long project;

    @BeforeEach void setup() throws Exception {
        owner = "Bearer " + auth.register(new RegisterRequest("Owner", "execution-owner@test.local", "password")).token();
        other = "Bearer " + auth.register(new RegisterRequest("Other", "execution-other@test.local", "password")).token();
        var response = mvc.perform(post("/api/projects").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CreateProjectRequest("Execution", "fixture"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        project = mapper.readTree(response).path("id").asLong();
        addSource("App", "public class App { public int add(int a, int b) { return a + b; } }");
        when(process.run(anyList(), any(), any(), any())).thenReturn(new ContainerProcess.Result(0, false, false,
                "{\"outcome\":\"SUCCESS\",\"exitCode\":0,\"output\":\"fixture worker evidence\",\"tests\":[{\"name\":\"adds\",\"status\":\"PASSED\",\"message\":\"\",\"seconds\":0.01}]}"));
    }

    private void addSource(String name, String content) throws Exception {
        mvc.perform(post("/api/projects/" + project + "/files").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CreateCodeFileRequest(name + ".java", "src/main/java/" + name + ".java", content))))
                .andExpect(status().isCreated());
    }

    private long draft(String state, String provider, boolean valid) throws Exception {
        var response = mvc.perform(get("/api/projects/" + project + "/test-plan").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var plan = mapper.readTree(response);
        var item = plan.path("items").get(0);
        String code = "import org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.Tag;\n"
                + "import static org.junit.jupiter.api.Assertions.assertEquals;\n@Tag(\"unit\") public class " + item.path("testName").asText()
                + " { @Test void adds() { assertEquals(5, new App().add(2, 3)); } }";
        var result = mapper.createObjectNode().put("status", state).put("provider", provider);
        result.set("plan", item);
        var generated = result.putObject("generated").put("testClass", item.path("testName").asText())
                .put("explanation", "Checks addition").put("fullTestCode", valid ? code : "invalid code");
        generated.putArray("tests").addObject().put("name", "adds").put("code", "assertEquals(5, new App().add(2, 3));");
        return drafts.saveAndFlush(new TestDraft(project, plan.path("snapshotId").asText(), result.toString())).getId();
    }
    private String endpoint(long draft) { return "/api/projects/" + project + "/test-drafts/" + draft + "/execution"; }

    @Test void executesAndPersistsEvidenceAndAllowsExplicitRetry() throws Exception {
        long draft = draft("STRUCTURALLY_VALIDATED", "fixture", true);
        mvc.perform(get(endpoint(draft)).header("Authorization", owner)).andExpect(status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(endpoint(draft)).header("Authorization", owner)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.result.outcome").value("SUCCESS"))
                    .andExpect(jsonPath("$.result.tests[0].name").value("adds"));
        }
        mvc.perform(get(endpoint(draft)).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.output").value("fixture worker evidence"));
        verify(process, times(2)).run(argThat(c -> c.get(1).equals("run")), any(), any(), any());
        verify(process, times(2)).run(argThat(c -> c.get(1).equals("rm")), any(), any(), any());
    }

    @Test void rejectsOtherUsersMockStaleAndInvalidDraftsBeforeLaunch() throws Exception {
        long valid = draft("STRUCTURALLY_VALIDATED", "fixture", true);
        mvc.perform(post(endpoint(valid)).header("Authorization", other)).andExpect(status().isForbidden());
        mvc.perform(get(endpoint(valid)).header("Authorization", other)).andExpect(status().isForbidden());
        mvc.perform(post(endpoint(valid))).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint(999999)).header("Authorization", owner)).andExpect(status().isNotFound());
        long mock = draft("MOCK_SCAFFOLD", "mock", true);
        mvc.perform(post(endpoint(mock)).header("Authorization", owner)).andExpect(status().isBadRequest());
        long invalid = draft("STRUCTURALLY_VALIDATED", "fixture", false);
        mvc.perform(post(endpoint(invalid)).header("Authorization", owner)).andExpect(status().isBadRequest());
        addSource("Extra", "public class Extra {}");
        mvc.perform(post(endpoint(valid)).header("Authorization", owner)).andExpect(status().isBadRequest());
        verifyNoInteractions(process);
    }

    @Test void dockerFailureIsPersistedWithoutSuccessOrHostFallback() throws Exception {
        long draft = draft("STRUCTURALLY_VALIDATED", "fixture", true);
        when(process.run(anyList(), any(), any(), any())).thenThrow(new java.io.IOException("Docker unavailable"));
        mvc.perform(post(endpoint(draft)).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.outcome").value("INFRASTRUCTURE_FAILURE"));
        mvc.perform(get(endpoint(draft)).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tests.length()").value(0));
    }
}
