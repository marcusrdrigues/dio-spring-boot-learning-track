package dio.budgeting.application.output;

import dio.budgeting.domain.Category;
import dio.budgeting.domain.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 001, correction P5: the amount is kept in cents and shown in reais. */
class TransactionOutputTest {

    @Test
    void showsTheAmountInReais() {
        var output = TransactionOutput.from(new Transaction("Farmácia", 5000, Category.PHARMA));

        assertThat(output.value()).isEqualTo(50.0);
        assertThat(output.category()).isEqualTo("PHARMA");
        assertThat(output.description()).isEqualTo("Farmácia");
    }

    @Test
    void keepsTheCents() {
        assertThat(TransactionOutput.from(new Transaction("Bala", 1, Category.GROCERIES)).value()).isEqualTo(0.01);
        assertThat(TransactionOutput.from(new Transaction("Pão", 1290, Category.GROCERIES)).value()).isEqualTo(12.9);
    }
}
