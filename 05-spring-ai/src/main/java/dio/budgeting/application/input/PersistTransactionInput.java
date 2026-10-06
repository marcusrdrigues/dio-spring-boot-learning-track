package dio.budgeting.application.input;

import dio.budgeting.domain.Category;

/** The data of a new transaction. The amount is in cents. */
public record PersistTransactionInput(String description, long amount, Category category) {
}
