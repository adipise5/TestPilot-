package com.testpilot.rag;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.service.RagIngestionService;
import com.testpilot.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "testpilot.execution.recovery-enabled=false",
        "testpilot.rag.vector-store=pgvector",
        "ai.embedding-provider=mock"
})
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "TEST_PGVECTOR", matches = "true")
class PgVectorIntegrationTest {

    @Autowired private ProjectRepository projects;
    @Autowired private RagIngestionService ingestion;
    @Autowired private RagService rag;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsVectorsAndExecutesScopedHybridQueryInPostgres() {
        Project project = projects.save(new Project("PgVector", "production vector smoke", 808L));
        String commit = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        ingestion.indexProject(project.getId(), commit, List.of(new CodeFile(
                project.getId(), "VectorSubject.java", "src/main/java/demo/VectorSubject.java",
                "package demo; public class VectorSubject { public String nearestNeighbour() { return \"ok\"; } }")),
                "plain-java");

        Integer vectors = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE project_id = ? AND embedding_vector IS NOT NULL",
                Integer.class,
                project.getId());
        assertNotNull(vectors);
        assertTrue(vectors > 0);

        var result = rag.retrieveForProject(project.getId(), commit, "nearestNeighbour", 5, 1000);
        assertFalse(result.results().isEmpty());
        assertTrue(result.results().stream().allMatch(item -> item.source().contains("VectorSubject.java")));
    }
}
