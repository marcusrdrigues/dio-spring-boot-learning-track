package dio.budgeting.infrastructure.ai;

import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.application.PersistTransactionUseCase;
import dio.budgeting.application.input.PersistTransactionInput;
import dio.budgeting.application.output.TransactionOutput;
import dio.budgeting.domain.Category;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Adapter: the tools the language model can call, each one delegating to a use case.
 *
 * <p>Spring AI stays here, in the infrastructure; the use cases do not know it. The parameters are
 * flat (no nested object), so the {@code ToolPolicy} can check each one by name.
 */
@Component
public class TransactionTools {
    public static final String PERSIST_TRANSACTION = "persist-transaction";
    public static final String LIST_TRANSACTIONS_BY_CATEGORY = "list-transactions-by-category";

    private final PersistTransactionUseCase persistTransaction;
    private final ListTransactionsByCategoryUseCase listTransactionsByCategory;

    public TransactionTools(PersistTransactionUseCase persistTransaction,
                            ListTransactionsByCategoryUseCase listTransactionsByCategory) {
        this.persistTransaction = persistTransaction;
        this.listTransactionsByCategory = listTransactionsByCategory;
    }

    @Tool(name = PERSIST_TRANSACTION, description = "Registra um novo gasto financeiro")
    public TransactionOutput persistTransaction(
            @ToolParam(description = "Descrição curta do gasto, como 'Compra no mercado'") String description,
            @ToolParam(description = "Valor do gasto em centavos, número inteiro. R$ 50,00 = 5000") long amountInCents,
            @ToolParam(description = "Categoria do gasto") Category category) {
        return persistTransaction.execute(new PersistTransactionInput(description, amountInCents, category));
    }

    @Tool(name = LIST_TRANSACTIONS_BY_CATEGORY, description = "Lista os gastos de uma categoria, com os valores em reais")
    public List<TransactionOutput> listTransactionsByCategory(
            @ToolParam(description = "Categoria dos gastos") Category category) {
        return listTransactionsByCategory.execute(category);
    }
}
