package ru.zaytsv.model;

import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Параметры запроса истории доходов ({@code GET /incomes}).
 *
 * <p>Все поля необязательны; задаются через fluent-сеттеры:</p>
 * <pre>{@code
 * IncomesQuery query = new IncomesQuery()
 *     .from(OffsetDateTime.now().minusMonths(1))
 *     .to(OffsetDateTime.now())
 *     .limit(50);
 * }</pre>
 */
@Getter
public class IncomesQuery {

    /** Начало периода (включительно). {@code null} — без нижней границы. */
    private OffsetDateTime from;

    /** Конец периода (включительно). {@code null} — без верхней границы. */
    private OffsetDateTime to;

    /** Максимальное число записей. По умолчанию 10. */
    private int limit = 10;

    /** Смещение (для пагинации). По умолчанию 0. */
    private int offset = 0;

    /** Сортировка. По умолчанию {@code operation_time:desc}. */
    private String sortBy = "operation_time:desc";

    public IncomesQuery from(OffsetDateTime from) {
        this.from = from;
        return this;
    }

    public IncomesQuery to(OffsetDateTime to) {
        this.to = to;
        return this;
    }

    public IncomesQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    public IncomesQuery offset(int offset) {
        this.offset = offset;
        return this;
    }

    public IncomesQuery sortBy(String sortBy) {
        this.sortBy = sortBy;
        return this;
    }
}
