package ru.zaytsv.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Страница истории доходов, возвращаемая {@code GET /incomes}.
 *
 * <p>Маппинг нестрогий: неизвестные поля ответа игнорируются.</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IncomesPage {

    /** Записи о доходах на текущей странице. */
    private List<IncomeRecord> content;

    /** Есть ли ещё записи за пределами текущей страницы. */
    private boolean hasMore;

    /** Общее число записей (если возвращается сервером). */
    private Integer currentLevelTotalRecords;

    /**
     * Запись о доходе (зарегистрированный чек).
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IncomeRecord {

        /** UUID чека. */
        private String approvedReceiptUuid;

        /** Наименование (первой услуги/сводное). */
        private String name;

        /** Время операции (ISO-8601). */
        private String operationTime;

        /** Время запроса/регистрации (ISO-8601). */
        private String requestTime;

        /** Тип оплаты: {@code CASH}, {@code ACCOUNT} и т.д. */
        private String paymentType;

        /** Итоговая сумма чека. */
        private BigDecimal totalAmount;

        /** Тип плательщика. */
        private String incomeType;

        /** Время аннулирования чека. {@code null}, если чек действует. */
        private String cancellationTime;
    }
}
