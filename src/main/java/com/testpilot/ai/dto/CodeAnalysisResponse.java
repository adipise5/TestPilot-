package com.testpilot.ai.dto;

import java.util.List;

public record CodeAnalysisResponse(
        String summary,
        List<String> classes,
        List<String> methods,
        List<String> edgeCases,
        List<String> testingRecommendations,
        List<String> potentialIssues
) {}
