package dio.budgeting.infrastructure.ai;

import com.marcusrdrigues.noxguard.agent.ToolCall;
import com.marcusrdrigues.noxguard.agent.ToolDecision;
import com.marcusrdrigues.noxguard.agent.ToolSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;
import tools.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Optional;

/**
 * Decorator: asks the {@link ToolSession} before the real tool runs.
 *
 * <ul>
 *   <li>{@code Run}: the tool runs and its result goes back to the model.
 *   <li>{@code Deny}: the tool does not run; the model gets the denial message, which never repeats
 *       what it sent, so it can ask the person again.
 *   <li>{@code Confirm}: no tool asks for confirmation yet, and this assistant has no screen to ask
 *       the person, so the call is denied (fail closed).
 * </ul>
 *
 * <p>Logs carry the tool name, the decision and only the arguments the policy marks as loggable;
 * never a description, which is free text from the person.
 */
final class GuardedToolCallback implements ToolCallback {
    static final String INVALID_JSON = "Error: the arguments are not valid JSON.";
    static final String NEEDS_CONFIRMATION = "Error: this action needs the person's confirmation, which this assistant cannot ask for.";

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private final ToolCallback tool;
    private final ToolSession session;

    GuardedToolCallback(ToolCallback tool, ToolSession session) {
        this.tool = tool;
        this.session = session;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return tool.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return tool.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        Optional<Map<String, Object>> arguments = parse(toolInput);
        if (arguments.isEmpty()) {
            session.decide(ToolCall.of(name)); // counts against the answer's limit; the tool does not run
            log.warn("Tool call denied: tool={} reason=INVALID_JSON", name);
            return INVALID_JSON;
        }
        return switch (session.decide(new ToolCall(name, arguments.get()))) {
            case ToolDecision.Run run -> {
                log.info("Tool call allowed: tool={} args={}", name, run.loggableArgs());
                yield tool.call(toolInput, toolContext);
            }
            case ToolDecision.Deny deny -> {
                log.warn("Tool call denied: tool={} reason={} argument={}", name, deny.reason(), deny.argument().orElse("-"));
                yield deny.messageForModel();
            }
            case ToolDecision.Confirm confirm -> {
                log.warn("Tool call denied: tool={} reason=NEEDS_CONFIRMATION", name);
                yield NEEDS_CONFIRMATION;
            }
        };
    }

    /** The model's JSON as a map; empty when it is not a JSON object. No input means no arguments. */
    private static Optional<Map<String, Object>> parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Optional.of(Map.of());
        }
        try {
            return Optional.ofNullable(JsonParser.fromJson(toolInput, ARGUMENTS));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
