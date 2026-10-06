package dio.budgeting.infrastructure.ai;

import com.marcusrdrigues.noxguard.agent.ArgRule;
import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.springai.GuardedToolCallbacks;
import dio.budgeting.domain.Category;
import dio.budgeting.domain.Transaction;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The guardrail at the AI edge: which tools the model may call, with which arguments and how many
 * times per answer (noxguard {@link ToolPolicy}, deny by default).
 *
 * <p>The domain still validates every transaction; this policy stops a bad call before any tool runs,
 * and also catches what the domain cannot see: unknown tools, extra arguments and too many calls.
 * {@code noxguard-spring-ai} puts every Spring AI tool under it (spec 002).
 */
@Configuration
public class GuardrailConfiguration {
    static final int MAX_PERSIST_CALLS = 3;
    static final int MAX_LIST_CALLS = 2;
    static final int MAX_CALLS_PER_ANSWER = 4;

    @Bean
    ToolPolicy toolPolicy() {
        return policy();
    }

    @Bean
    GuardedToolCallbacks guardedToolCallbacks(ToolPolicy toolPolicy, TransactionTools transactionTools) {
        return guarded(toolPolicy, List.of(ToolCallbacks.from(transactionTools)));
    }

    /**
     * The tools under the policy, with the decisions logged. No tool here needs the person's confirmation, so
     * there is no {@code onConfirm}; a tool exposed without a rule in the policy stops the app at startup.
     */
    static GuardedToolCallbacks guarded(ToolPolicy policy, List<ToolCallback> tools) {
        return GuardedToolCallbacks.builder(policy)
                .tools(tools)
                .listener(new ToolDecisionLog())
                .build();
    }

    /** The policy itself, without Spring, so it can be tested on its own. */
    static ToolPolicy policy() {
        String[] categories = Arrays.stream(Category.values()).map(Enum::name).toArray(String[]::new);
        return ToolPolicy.builder()
                .tool(TransactionTools.PERSIST_TRANSACTION, tool -> tool
                        .arg("description", ArgRule.required(), ArgRule.type(String.class), notBlank(),
                                ArgRule.maxLength(Transaction.MAX_DESCRIPTION_LENGTH))
                        .arg("amountInCents", ArgRule.required(), ArgRule.type(Number.class), wholePositiveCents())
                        .arg("category", ArgRule.required(), ArgRule.oneOf(categories))
                        .logArgs("category")
                        .maxCalls(MAX_PERSIST_CALLS))
                .tool(TransactionTools.LIST_TRANSACTIONS_BY_CATEGORY, tool -> tool
                        .arg("category", ArgRule.required(), ArgRule.oneOf(categories))
                        .logArgs("category")
                        .maxCalls(MAX_LIST_CALLS))
                .maxCalls(MAX_CALLS_PER_ANSWER)
                .build();
    }

    /** A string with something besides whitespace. */
    static ArgRule notBlank() {
        return value -> value instanceof String text && !text.isBlank()
                ? Optional.empty()
                : Optional.of("must not be blank");
    }

    /**
     * A whole number of cents, greater than zero and at most {@link Transaction#MAX_AMOUNT_CENTS}.
     *
     * <p>{@code 5000} and {@code 5000.0} are accepted; {@code 12.5} is not (there is no half cent). A
     * value that cannot be read as a number makes the rule throw, and noxguard denies the call (fail
     * closed).
     */
    static ArgRule wholePositiveCents() {
        BigDecimal max = BigDecimal.valueOf(Transaction.MAX_AMOUNT_CENTS);
        String aboveLimit = "must be at most " + Transaction.MAX_AMOUNT_CENTS + " cents";
        return value -> {
            BigDecimal amount = new BigDecimal(value.toString());
            if (amount.stripTrailingZeros().scale() > 0) {
                return Optional.of("must be a whole number of cents");
            }
            if (amount.signum() <= 0) {
                return Optional.of("must be greater than zero");
            }
            if (amount.compareTo(max) > 0) {
                return Optional.of(aboveLimit);
            }
            return Optional.empty();
        };
    }
}
