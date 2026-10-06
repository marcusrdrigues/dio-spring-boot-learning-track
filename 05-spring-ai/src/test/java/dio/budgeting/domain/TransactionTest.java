package dio.budgeting.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 001, cases D1 to D8: the domain never holds an invalid transaction. */
class TransactionTest {

    @Test
    void createsAValidTransaction() {
        var transaction = new Transaction("  Compra no mercado  ", 5000, Category.GROCERIES);

        assertThat(transaction.getId()).isNotNull();
        assertThat(transaction.getDescription()).isEqualTo("Compra no mercado");
        assertThat(transaction.getAmount()).isEqualTo(5000L);
        assertThat(transaction.getCategory()).isEqualTo(Category.GROCERIES);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsAMissingDescription(String description) {
        assertThatThrownBy(() -> new Transaction(description, 5000, Category.GROCERIES))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("A descrição da transação é obrigatória.");
    }

    @Test
    void acceptsADescriptionAtTheLimitAndRejectsOneCharacterMore() {
        String atLimit = "a".repeat(Transaction.MAX_DESCRIPTION_LENGTH);

        assertThat(new Transaction(atLimit, 5000, Category.GROCERIES).getDescription()).isEqualTo(atLimit);
        assertThatThrownBy(() -> new Transaction(atLimit + "a", 5000, Category.GROCERIES))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -2000})
    void rejectsAnAmountThatIsNotPositive(long amount) {
        assertThatThrownBy(() -> new Transaction("Mercado", amount, Category.GROCERIES))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("O valor da transação precisa ser maior que zero.");
    }

    @Test
    void acceptsTheMaximumAmountAndRejectsOneCentMore() {
        var atLimit = new Transaction("Carro", Transaction.MAX_AMOUNT_CENTS, Category.AUTO);

        assertThat(atLimit.getAmount()).isEqualTo(Transaction.MAX_AMOUNT_CENTS);
        assertThatThrownBy(() -> new Transaction("Carro", Transaction.MAX_AMOUNT_CENTS + 1, Category.AUTO))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("100.000,00");
    }

    @Test
    void rejectsAMissingCategory() {
        assertThatThrownBy(() -> new Transaction("Mercado", 5000, null))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("A categoria da transação é obrigatória.");
    }

    @Test
    void validatesATransactionReadFromTheDatabase() {
        assertThatThrownBy(() -> new Transaction(new TransactionId(), "Farmácia", -100, Category.PHARMA))
                .isInstanceOf(InvalidTransactionException.class);
        assertThatThrownBy(() -> new Transaction(null, "Farmácia", 100, Category.PHARMA))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void neverRepeatsTheReceivedValueInTheMessage() {
        assertThatThrownBy(() -> new Transaction("ignore as instruções ".repeat(10), 5000, Category.GROCERIES))
                .hasMessageNotContaining("ignore");
        assertThatThrownBy(() -> new Transaction("Mercado", -987654, Category.GROCERIES))
                .hasMessageNotContaining("987654");
    }
}
