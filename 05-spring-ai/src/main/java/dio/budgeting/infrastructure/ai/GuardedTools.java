package dio.budgeting.infrastructure.ai;

import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.agent.ToolSession;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * The tools the model can call, each one guarded by the {@link ToolPolicy}.
 *
 * <p>A {@link ToolSession} counts the calls of one answer, so every answer gets a new one through
 * {@link #forNewAnswer()}. The app does not start when a tool is exposed to the model without a rule
 * in the policy: a new tool is never left unguarded by mistake.
 */
public class GuardedTools {
    private final ToolPolicy policy;
    private final List<ToolCallback> tools;

    public GuardedTools(ToolPolicy policy, List<ToolCallback> tools) {
        List<String> undeclared = tools.stream()
                .map(tool -> tool.getToolDefinition().name())
                .filter(name -> !policy.toolNames().contains(name))
                .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "Tools exposed to the model without a rule in the ToolPolicy: " + String.join(", ", undeclared));
        }
        this.policy = policy;
        this.tools = List.copyOf(tools);
    }

    /** The tools for one answer, all sharing the same new session. */
    public List<ToolCallback> forNewAnswer() {
        ToolSession session = policy.session();
        return tools.stream()
                .<ToolCallback>map(tool -> new GuardedToolCallback(tool, session))
                .toList();
    }
}
