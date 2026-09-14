package benchmark.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRepositoryGeneratedTest {
    @Test
    @Tag("integration")
    void persistsAndCountsOneUser() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:dense_only")) {
            UserRepository repository = new UserRepository(connection);
            repository.initialize();
            repository.save("one@example.com");
            assertEquals(1, repository.countByDomain("example.com"));
        }
    }
}
