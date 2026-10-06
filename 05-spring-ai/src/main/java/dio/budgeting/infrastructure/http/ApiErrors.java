package dio.budgeting.infrastructure.http;

import dio.budgeting.domain.InvalidTransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns domain errors into HTTP responses (Problem Details, RFC 9457). */
@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(InvalidTransactionException.class)
    ProblemDetail invalidTransaction(InvalidTransactionException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Transação inválida");
        return problem;
    }
}
