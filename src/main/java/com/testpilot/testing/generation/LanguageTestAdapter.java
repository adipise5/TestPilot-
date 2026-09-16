package com.testpilot.testing.generation;

import com.testpilot.ai.dto.TestGenerationResponse;
import com.testpilot.testing.entity.TestLevel;
import java.util.List;

/** Planning and static validation only. Adapters never run repository code. */
public interface LanguageTestAdapter {
    boolean supports(String path);
    TestPlanItem plan(SourceInput source, TestLevel level, List<SourceInput> context, String snapshot);
    String instructions(TestPlanItem plan);
    List<String> validate(TestPlanItem plan, TestGenerationResponse response, boolean mock);
}
