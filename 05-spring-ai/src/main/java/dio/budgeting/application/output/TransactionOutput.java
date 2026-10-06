package dio.budgeting.application.output;

import dio.budgeting.domain.Transaction;

import java.math.BigDecimal;

/**
 * A transaction as the use cases return it.
 *
 * <p>The domain keeps the amount in cents; {@code value} is the same amount in reais
 * (5000 cents become 50.00).
 */
public record TransactionOutput(String id, String description, String category, double value) {
    private static final int CENTS_SCALE = 2;

    public static TransactionOutput from(Transaction transaction) {
        return new TransactionOutput(
                transaction.getId().uuid().toString(),
                transaction.getDescription(),
                transaction.getCategory().name(),
                BigDecimal.valueOf(transaction.getAmount(), CENTS_SCALE).doubleValue());
    }
}
