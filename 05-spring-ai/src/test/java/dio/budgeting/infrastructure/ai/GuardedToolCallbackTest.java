package dio.budgeting.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static dio.budgeting.infrastructure.ai.TransactionTools.LIST_TRANSACTIONS_BY_CATEGORY;
import static dio.budgeting.infrastructure.ai.TransactionTools.PERSIST_TRANSACTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 001, cases G1 to G5: the decorator runs the real tool only when the policy allows it. */
class GuardedToolCallbackTest {
    private static final String VALID = "{\"description\":\"Farmácia\",\"amountInCents\":5000,\"category\":\"PHARMA\"}";
    private static final String NEGATIVE = "{\"description\":\"Mercado\",\"amountInCents\":-2000,\"category\":\"GROCERIES\"}";
    private static final String TOOL_RESULT = "{\"ok\":true}";

    private final FakeTool persist = new FakeTool(PERSIST_TRANSACTION);
    private final FakeTool list = new FakeTool(LIST_TRANSACTIONS_BY_CATEGORY);
    private final GuardedTools tools = new GuardedTools(GuardrailConfiguration.policy(), List.of(persist, list));

    @Test
    void runsTheToolWhenThePolicyAllows() {
        String result = named(tools.forNewAnswer(), PERSIST_TRANSACTION).call(VALID);

        assertThat(result).isEqualTo(TOOL_RESULT);
        assertThat(persist.calls).isEqualTo(1);
    }

    @Test
    void doesNotRunTheToolWhenThePolicyDenies() {
        String result = named(tools.forNewAnswer(), PERSIST_TRANSACTION).call(NEGATIVE);

        assertThat(result).startsWith("Error: invalid argument \"amountInCents\"");
        assertThat(persist.calls).isEqualTo(0);
    }

    @Test
    void deniesInvalidJsonAndCountsTheCall() {
        ToolCallback guarded = named(tools.forNewAnswer(), PERSIST_TRANSACTION);

        for (int i = 0; i < GuardrailConfiguration.MAX_CALLS_PER_ANSWER; i++) {
            assertThat(guarded.call("not json")).isEqualTo(GuardedToolCallback.INVALID_JSON);
        }

        assertThat(guarded.call(VALID)).contains("tool limit reached");
        assertThat(persist.calls).isEqualTo(0);
    }

    @Test
    void startsEveryAnswerWithItsOwnCounts() {
        ToolCallback first = named(tools.forNewAnswer(), PERSIST_TRANSACTION);
        for (int i = 0; i < GuardrailConfiguration.MAX_PERSIST_CALLS; i++) {
            assertThat(first.call(VALID)).isEqualTo(TOOL_RESULT);
        }
        assertThat(first.call(VALID)).contains("limit");

        ToolCallback second = named(tools.forNewAnswer(), PERSIST_TRANSACTION);

        assertThat(second.call(VALID)).isEqualTo(TOOL_RESULT);
        assertThat(persist.calls).isEqualTo(GuardrailConfiguration.MAX_PERSIST_CALLS + 1);
    }

    @Test
    void refusesToStartWithAToolThatThePolicyDoesNotDeclare() {
        var unguarded = new FakeTool("delete-all-transactions");

        assertThatThrownBy(() -> new GuardedTools(GuardrailConfiguration.policy(), List.of(persist, unguarded)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delete-all-transactions");
    }

    private static ToolCallback named(List<ToolCallback> tools, String name) {
        return tools.stream()
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** A tool that only counts how many times it ran. */
    private static final class FakeTool implements ToolCallback {
        private final ToolDefinition definition;
        private int calls;

        private FakeTool(String name) {
            this.definition = ToolDefinition.builder().name(name).description("fake tool").inputSchema("{}").build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return TOOL_RESULT;
        }
    }
}
