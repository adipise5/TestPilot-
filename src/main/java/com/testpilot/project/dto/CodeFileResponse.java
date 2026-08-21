package com.testpilot.project.dto;

import com.testpilot.project.entity.CodeFile;
import java.time.LocalDateTime;

public record CodeFileResponse(
        Long id,
        Long projectId,
        String fileName,
        String filePath,
        String content,
        LocalDateTime createdAt
) {
    public static CodeFileResponse fromEntity(CodeFile codeFile) {
        return new CodeFileResponse(
                codeFile.getId(),
                codeFile.getProjectId(),
                codeFile.getFileName(),
                codeFile.getFilePath(),
                codeFile.getContent(),
                codeFile.getCreatedAt()
        );
    }
}
