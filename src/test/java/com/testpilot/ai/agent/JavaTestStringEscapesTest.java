package com.testpilot.ai.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaTestStringEscapesTest {
    @Test
    void repairsIllegalPathEscapesAndPreservesValidJavaEscapes() {
        String invalid = "String path = \"src" + '\\' + "main" + '\\' + "java\";";
        String expected = "String path = \"src" + "\\\\" + "main" + "\\\\" + "java\";";
        assertEquals(expected, JavaTestStringEscapes.repair(invalid));

        String valid = "String newline = \"\\n\"; String slash = \"\\\\\";";
        assertEquals(valid, JavaTestStringEscapes.repair(valid));
    }

    @Test
    void addsMissingJunitAnnotationImportsAfterPackage() {
        String source = "package example;\n@Tag(\"module\") class SampleTest { @Test void works() {} }";
        String fixed = JavaTestStringEscapes.repair(source);
        assertEquals(true, fixed.contains("package example;\nimport org.junit.jupiter.api.Test;\nimport org.junit.jupiter.api.Tag;"));
    }
}
