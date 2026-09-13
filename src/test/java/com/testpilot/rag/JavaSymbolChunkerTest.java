package com.testpilot.rag;

import com.testpilot.rag.chunking.JavaSymbolChunker;
import com.testpilot.rag.entity.RagChunkKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaSymbolChunkerTest {

    @Test
    void chunksJavaAtTypeAndMethodBoundariesWithLineProvenance() {
        String source = """
                package com.example.orders;
                import java.util.Objects;

                public class OrderService {
                    public String place(String id) {
                        return Objects.requireNonNull(id);
                    }

                    private void audit() {
                    }
                }
                """;

        var chunks = new JavaSymbolChunker().chunk(source);

        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.kind() == RagChunkKind.JAVA_TYPE && "OrderService".equals(chunk.symbol())));
        var method = chunks.stream().filter(chunk -> "place".equals(chunk.symbol())).findFirst().orElseThrow();
        assertEquals(RagChunkKind.JAVA_METHOD, method.kind());
        assertTrue(method.startLine() < method.endLine());
        assertTrue(method.content().contains("package com.example.orders"));
        assertTrue(method.content().contains("return Objects.requireNonNull(id)"));
    }
}
