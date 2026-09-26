package com.testpilot.testing.workflow.service;

import com.testpilot.project.entity.CodeFile;
import java.util.Comparator;
import java.util.List;

/** Choose a stable behavioral target while retaining the full repository for mapping and execution. */
final class WorkflowSourceSelector {
    private WorkflowSourceSelector() {}

    static List<CodeFile> order(List<CodeFile> files) {
        return files.stream().sorted(Comparator.comparingInt(WorkflowSourceSelector::priority)
                .thenComparing(CodeFile::getFilePath)).toList();
    }

    static boolean needsFrameworkWiring(String source) {
        return source.matches("(?s).*(?:@SpringBootApplication|@RestController|@Controller|@Service|@Repository|@Configuration|@Autowired|ApplicationContext|org\\.springframework\\.(?:web|data|jdbc)).*");
    }

    private static int priority(CodeFile file) {
        String path = file.getFilePath();
        String code = file.getContent();
        if (!path.endsWith(".java")) return 5;
        if (code.contains("@SpringBootApplication") || code.contains("public static void main(")) return 4;
        if (!code.contains(" class ") || code.contains(" interface ") || code.contains(" enum ") || code.contains(" record ")) return 3;
        if (path.contains("/validation/") || path.contains("/util/") || path.contains("/utils/")) return 0;
        return 1;
    }
}
