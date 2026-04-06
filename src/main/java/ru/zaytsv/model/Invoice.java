package ru.zaytsv.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Счёт, выставленный через API сервиса «Мой налог».
 *
 * <p>Возвращается методом {@code MoyNalogClient.createInvoice()}.</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Invoice {

    /** Числовой идентификатор счёта. */
    private Long invoiceId;

    /** UUID счёта. */
    private String uuid;

    /**
     * Статус счёта: {@code CREATED}, {@code PAID}, {@code CANCELLED} и т.д.
     */
    private String status;

    /**
     * Ссылка на страницу оплаты счёта.
     * Может быть {@code null} для счёта с типом оплаты {@code ACCOUNT}.
     */
    private String transitionPageURL;

    /** Тип оплаты: {@code ACCOUNT}, {@code CASH}, {@code CARD} и т.д. */
    private String paymentType;

    /** Наименование банка. */
    private String bankName;

    /** БИК банка. */
    private String bankBik;

    /** Расчётный счёт. */
    private String currentAccount;

    /** Корреспондентский счёт. */
    private String corrAccount;

    /** Тип плательщика. */
    private ClientType clientType;

    /** Наименование клиента (плательщика). */
    private String clientName;

    /** ИНН клиента. */
    private String clientInn;

    /** Итоговая сумма по счёту (в рублях). */
    private BigDecimal totalAmount;

    /** Сумма налога. */
    private BigDecimal totalTax;

    /** Список услуг. */
    private List<ServiceItem> services;

    /** Время создания счёта (ISO-8601 с часовым поясом). */
    private String createdAt;

    /** Время оплаты счёта. {@code null}, если ещё не оплачен. */
    private String paidAt;

    /** Время отмены счёта. {@code null}, если не отменён. */
    private String cancelledAt;

    /**
     * Позиция в счёте.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceItem {
        private String name;
        private Integer quantity;
        private Integer serviceNumber;
        private BigDecimal amount;
    }
}
