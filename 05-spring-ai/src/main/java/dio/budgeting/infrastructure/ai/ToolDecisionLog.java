package dio.budgeting.infrastructure.ai;

import com.marcusrdrigues.noxguard.agent.ToolDecision;
import com.marcusrdrigues.noxguard.springai.ToolDecisionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs every decision of the tool policy: the tool, the decision and only the arguments the policy marks as
 * loggable (the category). The description, free text from the person, never reaches the log, and neither do the
 * arguments of a denied call.
 */
final class ToolDecisionLog implements ToolDecisionListener {
    private static final Logger log = LoggerFactory.getLogger(ToolDecisionLog.class);

    @Override
    public void onDecision(String tool, ToolDecision decision) {
        if (decision instanceof ToolDecision.Deny) {
            log.warn(line(tool, decision));
        } else {
            log.info(line(tool, decision));
        }
    }

    /** The log line, apart from the logger, so it can be tested. */
    static String line(String tool, ToolDecision decision) {
        return switch (decision) {
            case ToolDecision.Run run -> "Tool call allowed: tool=" + tool + " args=" + run.loggableArgs();
            case ToolDecision.Confirm confirm -> "Tool call held for confirmation: tool=" + tool + " args=" + confirm.loggableArgs();
            case ToolDecision.Deny deny -> "Tool call denied: tool=" + tool + " reason=" + deny.reason()
                    + " argument=" + deny.argument().orElse("-");
        };
    }
}
