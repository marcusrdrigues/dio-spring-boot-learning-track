package dio.budgeting.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * A financial transaction. It never exists in an invalid state, whether it comes from the REST API,
 * from the AI assistant or from the database.
 */
@Getter
public class Transaction {
    /** Longest description, counted in Unicode code points. */
    public static final int MAX_DESCRIPTION_LENGTH = 120;

    /** Highest amount of one transaction, in cents (R$ 100.000,00). */
    public static final long MAX_AMOUNT_CENTS = 10_000_000L;

    private final TransactionId id;
    private final String description;
    private final long amount;
    private final Category category;

    /** A new transaction. The amount is in cents. */
    public Transaction(String description, long amount, Category category) {
        this(new TransactionId(), description, amount, category);
    }

    /** A transaction that already exists (read from the database). The amount is in cents. */
    public Transaction(TransactionId id, String description, long amount, Category category) {
        if (id == null) {
            throw new InvalidTransactionException("A transação precisa de um identificador.");
        }
        this.id = id;
        this.description = validDescription(description);
        this.amount = validAmount(amount);
        this.category = validCategory(category);
    }

    private static String validDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new InvalidTransactionException("A descrição da transação é obrigatória.");
        }
        String trimmed = description.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidTransactionException(
                    "A descrição da transação pode ter no máximo " + MAX_DESCRIPTION_LENGTH + " caracteres.");
        }
        return trimmed;
    }

    private static long validAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidTransactionException("O valor da transação precisa ser maior que zero.");
        }
        if (amount > MAX_AMOUNT_CENTS) {
            throw new InvalidTransactionException("O valor da transação passa do limite de " + maxAmountInReais() + ".");
        }
        return amount;
    }

    private static Category validCategory(Category category) {
        if (category == null) {
            throw new InvalidTransactionException("A categoria da transação é obrigatória.");
        }
        return category;
    }

    private static String maxAmountInReais() {
        return NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(BigDecimal.valueOf(MAX_AMOUNT_CENTS, 2));
    }
}
