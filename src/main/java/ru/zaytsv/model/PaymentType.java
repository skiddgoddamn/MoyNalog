package ru.zaytsv.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Реквизиты для выставления счёта (способ оплаты из личного кабинета).
 *
 * <p>Получают методом {@code MoyNalogClient.getPaymentTypes()}.</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentType {

    /** Идентификатор записи в системе ФНС. */
    private Long id;

    /** Тип реквизитов: {@code ACCOUNT} — расчётный счёт, {@code CARD} — карта и т.д. */
    private String type;

    /** Наименование банка. */
    private String bankName;

    /** БИК банка. */
    private String bankBik;

    /** Расчётный счёт. */
    private String currentAccount;

    /** Корреспондентский счёт. */
    private String corrAccount;

    /** Номер телефона (для типа CARD). */
    private String phone;

    /** Является ли реквизит избранным. */
    private Boolean favorite;

    /** Доступен ли реквизит для партнёрского API. */
    private Boolean availableForPa;
}
