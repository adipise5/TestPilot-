package com.testpilot.rag;

import com.testpilot.project.entity.CodeFile;
import com.testpilot.project.entity.Project;
import com.testpilot.project.repository.ProjectRepository;
import com.testpilot.rag.service.RagIngestionService;
import com.testpilot.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("h2")
@Transactional
class RagIsolationIntegrationTest {

    @Autowired private ProjectRepository projects;
    @Autowired private RagIngestionService ingestion;
    @Autowired private RagService rag;

    @Test
    void ingestionIsIdempotentAndRetrievalCannotCrossProjectScope() {
        Project billing = projects.save(new Project("Billing", "tenant one", 101L));
        Project payroll = projects.save(new Project("Payroll", "tenant two", 202L));
        String commit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        CodeFile billingFile = new CodeFile(
                billing.getId(), "BillingLedger.java", "src/main/java/acme/billing/BillingLedger.java",
                "package acme.billing; public class BillingLedger { public int calculateInvoice() { return 7; } }");
        CodeFile payrollFile = new CodeFile(
                payroll.getId(), "PayrollVault.java", "src/main/java/other/payroll/PayrollVault.java",
                "package other.payroll; public class PayrollVault { public int confidentialSalary() { return 99; } }");

        var first = ingestion.indexProject(billing.getId(), commit, List.of(billingFile), "plain-java");
        var repeated = ingestion.indexProject(billing.getId(), commit, List.of(billingFile), "plain-java");
        ingestion.indexProject(payroll.getId(), commit, List.of(payrollFile), "plain-java");

        assertEquals(1, first.documentsCreated());
        assertTrue(first.chunksCreated() > 0);
        assertEquals(1, repeated.documentsReused());
        assertEquals(0, repeated.chunksCreated());

        var result = rag.retrieveForTesting(
                billing.getId(), commit, "calculateInvoice BillingLedger", 7001L);
        assertFalse(result.results().isEmpty());
        assertTrue(result.results().stream().allMatch(item -> item.source().contains("acme/billing")));
        assertFalse(result.context().contains("confidentialSalary"));
        assertTrue(result.citations().stream().allMatch(citation ->
                citation.uri().contains("/project/" + billing.getId() + "/commit/" + commit)));

        var trace = rag.tracesForTestRun(7001L).get(0);
        assertEquals(result.queryHash(), trace.queryHash());
        assertEquals("calculateInvoice BillingLedger", trace.queryText());
        assertEquals(result.context(), trace.packedContext());
        assertEquals(result.citations(), trace.citations());

        var constrained = rag.retrieveForProject(
                billing.getId(), commit, "calculateInvoice BillingLedger", 8, 256);
        assertTrue(constrained.packedTokens() <= 256);
        assertTrue(constrained.results().stream()
                .mapToInt(com.testpilot.rag.dto.RagQueryResult::tokenCount)
                .sum() <= 256);
    }
}
