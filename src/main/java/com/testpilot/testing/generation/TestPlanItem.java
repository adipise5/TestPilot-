package com.testpilot.testing.generation;

import com.testpilot.testing.entity.TestLevel;

public record TestPlanItem(String id, String sourcePath, String language, String framework,
                           TestLevel level, String testName, String outputPath,
                           boolean applicable, String objective) {}
