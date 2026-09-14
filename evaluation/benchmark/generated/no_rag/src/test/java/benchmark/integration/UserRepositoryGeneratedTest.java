package benchmark.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRepositoryGeneratedTest {
    @Test
    @Tag("integration")
    void initializesAnEmptyRepository() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:no_rag")) {
            UserRepository repository = new UserRepository(connection);
            repository.initialize();
            assertEquals(0, repository.countByDomain("example.com"));
        }
    }
}
