package com.testpilot.rag.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.ai.client.LlmClient;
import com.testpilot.common.validation.RepositoryPathPolicy;
import com.testpilot.review.*;
import com.testpilot.testing.generation.SourceInput;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class Phase6ContextBenchmarkTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SnapshotContextRetriever retriever = new SnapshotContextRetriever(new LanguageStandards(mapper), new RepositoryPathPolicy());
    private final ReviewAgent agent = new ReviewAgent(mock(LlmClient.class), mapper);

    @Test void pinnedMultilingualBenchmarkUsesProductionRetrievalAndCitationValidation() throws Exception {
        var path = Path.of("evaluation/phase6/context-v1.json");
        String json = Files.readString(path);
        assertEquals(Files.readString(Path.of("evaluation/phase6/context-v1.sha256")).trim(), SnapshotContextRetriever.hash(json));
        var cases = mapper.readTree(json).path("cases");
        List<Map<String,Object>> evidence = new ArrayList<>();
        int hits=0, accepted=0, rejected=0;
        for (var item : cases) {
            var primary = mapper.treeToValue(item.path("primary"), SourceInput.class);
            var related = mapper.treeToValue(item.path("related"), SourceInput.class);
            var context = retriever.retrieve("snapshot-A", List.of(primary), List.of(primary, related));
            assertFalse(context.snippets().isEmpty(), item.path("id").asText());
            assertEquals(item.path("expectedPath").asText(), context.snippets().get(0).path()); hits++;
            assertTrue(context.standards().stream().anyMatch(r -> r.id().equals(item.path("expectedStandard").asText())));
            var c = context.snippets().get(0);
            String first = primary.content().lines().findFirst().orElseThrow();
            var p = new ReviewReport.Evidence(primary.path(), 1, 1, first);
            var r = new ReviewReport.Evidence(c.path(), c.startLine(), c.startLine(), c.content().lines().findFirst().orElseThrow());
            var good = finding(List.of(p,r), List.of(item.path("expectedStandard").asText()));
            var valid = agent.validate(new ReviewReport.BatchResponse(List.of(primary.path()), List.of(good)), List.of(primary), context);
            assertEquals(1, valid.findings().size()); accepted++;
            var bad = List.of(finding(List.of(r), List.of()),
                    finding(List.of(p,new ReviewReport.Evidence("invented.file",1,1,"invented")), List.of()),
                    finding(List.of(p,new ReviewReport.Evidence(c.path(), c.endLine()+1, c.endLine()+1,"outside excerpt")), List.of()),
                    finding(List.of(p), List.of("hallucinated.standard")),
                    finding(List.of(p,new ReviewReport.Evidence(c.path(), c.startLine(), c.startLine(),"changed snippet")), List.of()));
            var invalid = agent.validate(new ReviewReport.BatchResponse(List.of(primary.path()), bad), List.of(primary), context);
            assertEquals(bad.size(), invalid.rejected()); assertTrue(invalid.findings().isEmpty()); rejected+=invalid.rejected();
            evidence.add(Map.of("caseId",item.path("id").asText(),"retrieval",context,"acceptedGroundedFixture",1,"rejectedAdversarialFixtures",invalid.rejected()));
        }
        var output = Map.of("datasetSha256", SnapshotContextRetriever.hash(json), "indexVersion", SnapshotContextRetriever.VERSION,
                "cases", evidence, "retrievalHitAt1", hits/(double)cases.size(), "validFixtureAcceptance", accepted/(double)cases.size(),
                "invalidFixtureRejection", rejected/(double)(cases.size()*5), "liveModelEvaluation", false);
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/phase6-context-evidence.json").toFile(),output);
    }
    private ReviewReport.Finding finding(List<ReviewReport.Evidence> evidence, List<String> standards) {
        return new ReviewReport.Finding("IMPROVEMENT","CORRECTNESS","LOW","Fixture finding", "Fixture evaluates grounding, not semantic correctness",
                "Review this fixture contract; no real defect is asserted",evidence,standards);
    }
    @Test void retrievalIsDeterministicSnapshotScopedAndIgnoresCommentsAndStrings() {
        var primary = new SourceInput("app.py", "# hidden_helper()\ntext = 'hidden_helper()'\ndef run(): return 1\n");
        var helper = new SourceInput("helper.py", "def hidden_helper(): return 2\n");
        var context = retriever.retrieve("A",List.of(primary),List.of(primary,helper));
        assertTrue(context.snippets().isEmpty());
        var actual = new SourceInput("app.py", "from helper import hidden_helper\ndef run(): return hidden_helper()\n");
        var one = retriever.retrieve("A",List.of(actual),List.of(actual,helper));
        var two = retriever.retrieve("A",List.of(actual),List.of(helper,actual));
        assertEquals(one,two);
        assertNotEquals(one.queryHash(),retriever.retrieve("B",List.of(actual),List.of(actual,helper)).queryHash());
        assertThrows(RuntimeException.class,()->retriever.retrieve("A",List.of(actual),List.of(primary,helper)));
        assertTrue(retriever.retrieve("B",List.of(actual),List.of(actual)).snippets().isEmpty());
    }
    @Test void enforcesBudgetsAndNeverReturnsSensitiveOrUnsupportedContent() {
        var primary = new SourceInput("app.py", "def run(): return helper()\n");
        List<SourceInput> files = new ArrayList<>(List.of(primary,new SourceInput(".env","def helper(): return 'secret'"),new SourceInput("other.go","class helper {}")));
        for(int i=0;i<30;i++) files.add(new SourceInput("helper"+i+".py","def helper():\n"+"    value = 1\n".repeat(100)));
        var context = retriever.retrieve("A",List.of(primary),files);
        assertTrue(context.snippets().size()<=6); assertTrue(context.usedCharacters()<=12000);
        assertTrue(context.snippets().stream().noneMatch(c->c.path().equals(".env")||c.path().endsWith(".go")));
        for(var c:context.snippets()) {
            String full = files.stream().filter(f->f.path().equals(c.path())).findFirst().orElseThrow().content();
            assertEquals(SnapshotContextRetriever.hash(full),c.sourceHash());
            assertEquals(String.join("\n",full.lines().skip(c.startLine()-1).limit(c.endLine()-c.startLine()+1).toList()),c.content());
        }
    }
    @Test void contextDoesNotAllowCitationOfUnreviewedPrimaryOrAcknowledgementOfContextOnlyFiles() {
        var primary = new SourceInput("app.py","def run(): return helper()\n");
        var helper = new SourceInput("helper.py","def helper(): return 1\n");
        var context = retriever.retrieve("A",List.of(primary),List.of(primary,helper));
        assertThrows(IllegalArgumentException.class,()->agent.validate(new ReviewReport.BatchResponse(List.of("helper.py"),List.of()),List.of(primary),context));
    }
    @Test void rejectsRealLinesOutsideSuppliedExcerptAndStandardsFromAnotherPrimaryLanguage() {
        var primary = new SourceInput("app.py", "def run(): return helper()\n");
        var java = new SourceInput("Other.java", "class Other {}\n");
        var helper = new SourceInput("helper.py", "def helper():\n" + "    value = 1\n".repeat(50));
        var context = retriever.retrieve("A", List.of(primary, java), List.of(primary, java, helper));
        assertEquals(40, context.snippets().get(0).endLine());
        var anchor = new ReviewReport.Evidence("app.py", 1, 1, "def run(): return helper()");
        var outside = new ReviewReport.Evidence("helper.py", 45, 45, "    value = 1");
        var invalid = List.of(finding(List.of(anchor, outside), List.of()), finding(List.of(anchor), List.of("java.resources")));
        var checked = agent.validate(new ReviewReport.BatchResponse(List.of("app.py", "Other.java"), invalid), List.of(primary, java), context);
        assertEquals(2, checked.rejected()); assertTrue(checked.findings().isEmpty());
    }
    @Test void oldReportsDeserializeWithEmptyRetrievalAndStandards() throws Exception {
        String fixture = Files.readString(Path.of("docs/verification/phase5-fixture-review.json"));
        var report = mapper.treeToValue(mapper.readTree(fixture).path("report"),ReviewReport.class);
        assertTrue(report.retrieval().isEmpty());
        assertTrue(report.findings().stream().allMatch(f->f.standardIds().isEmpty()));
    }
}
