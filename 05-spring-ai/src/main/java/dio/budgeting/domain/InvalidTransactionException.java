package dio.budgeting.domain;

/**
 * A transaction that breaks one of its rules.
 *
 * <p>The message is fixed text and never repeats the value that was received: it may reach the
 * language model (a tool's exception becomes the tool result) and an API client.
 */
public class InvalidTransactionException extends IllegalArgumentException {

    public InvalidTransactionException(String message) {
        super(message);
    }
}
