package dio.budgeting.infrastructure.ai;

import com.marcusrdrigues.noxguard.agent.ToolCall;
import com.marcusrdrigues.noxguard.agent.ToolDecision;
import com.marcusrdrigues.noxguard.agent.ToolSession;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dio.budgeting.infrastructure.ai.TransactionTools.PERSIST_TRANSACTION;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec 002, cases L1 and L2: the log carries the decision, never the person's free text nor a rejected value. */
class ToolDecisionLogTest {
    private final ToolSession session = GuardrailConfiguration.policy().session();

    @Test
    void logsTheCategoryButNeverTheDescription() {
        ToolDecision run = session.decide(new ToolCall(PERSIST_TRANSACTION,
                Map.of("description", "Consulta com a Dra. Ana", "amountInCents", 25000, "category", "PHARMA")));

        String line = ToolDecisionLog.line(PERSIST_TRANSACTION, run);

        assertThat(line).isEqualTo("Tool call allowed: tool=persist-transaction args={category=PHARMA}");
        assertThat(line).doesNotContain("Dra. Ana").doesNotContain("25000");
    }

    @Test
    void logsTheReasonOfADenialButNeverTheValueSent() {
        ToolDecision deny = session.decide(new ToolCall(PERSIST_TRANSACTION,
                Map.of("description", "Mercado", "amountInCents", -2000, "category", "GROCERIES")));

        String line = ToolDecisionLog.line(PERSIST_TRANSACTION, deny);

        assertThat(line).isEqualTo("Tool call denied: tool=persist-transaction reason=ARGUMENT argument=amountInCents");
        assertThat(line).doesNotContain("-2000").doesNotContain("Mercado");
    }
}
