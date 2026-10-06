package dio.budgeting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Starts the whole app: it needs the OpenAI key and the database, as the *IT tests do. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class BudgetingApplicationTests {

    @Test
    void contextLoads() {
    }

}
