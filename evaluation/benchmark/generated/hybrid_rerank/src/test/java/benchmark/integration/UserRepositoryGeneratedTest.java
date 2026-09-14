package benchmark.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRepositoryGeneratedTest {
    @Test
    @Tag("integration")
    void persistsAndFiltersAcrossARealInMemoryDatabaseBoundary() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:hybrid_rerank")) {
            UserRepository repository = new UserRepository(connection);
            repository.initialize();
            repository.save("one@example.com");
            repository.save("two@example.com");
            repository.save("three@other.test");
            assertEquals(2, repository.countByDomain("example.com"));
            assertEquals(1, repository.countByDomain("other.test"));
        }
    }
}
