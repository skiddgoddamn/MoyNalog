package ru.zaytsv.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Сводка по налогу и начислениям, возвращаемая {@code GET /taxes}.
 *
 * <p>Маппинг нестрогий: неизвестные поля ответа игнорируются. Состав полей ответа ФНС
 * может меняться; отсутствующие в ответе значения остаются {@code null}.</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaxInfo {

    /** Сумма налога к уплате. */
    private BigDecimal taxToPay;

    /** Задолженность. */
    private BigDecimal debt;

    /** Сумма пеней. */
    private BigDecimal penalty;

    /** Остаток налогового бонуса (вычета). */
    private BigDecimal bonusAmount;

    /** Неоплаченная сумма всего. */
    private BigDecimal unpaidAmount;
}
