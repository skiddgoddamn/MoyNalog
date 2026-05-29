package ru.zaytsv.model;

import java.math.BigDecimal;

/**
 * Позиция (услуга/товар), включаемая в чек.
 *
 * <p><b>Важно:</b> {@code amount} — цена за единицу, а не итоговая сумма.
 * Итог рассчитывается автоматически: {@code amount × quantity}.</p>
 *
 * <p>С версии 1.1.5 сумма хранится в {@link BigDecimal} (точные деньги).
 * Конструктор с {@code double} сохранён для совместимости:</p>
 * <pre>{@code
 * new IncomeItem("Консультация", 1, 5000.0);                 // double
 * new IncomeItem("Консультация", 1, new BigDecimal("5000")); // BigDecimal
 * }</pre>
 *
 * @param name     наименование услуги или товара
 * @param quantity количество единиц
 * @param amount   цена за одну единицу (в рублях)
 */
public record IncomeItem(String name, int quantity, BigDecimal amount) {

    /**
     * Удобный конструктор с ценой типа {@code double}.
     *
     * @param name     наименование услуги или товара
     * @param quantity количество единиц
     * @param amount   цена за одну единицу (в рублях)
     */
    public IncomeItem(String name, int quantity, double amount) {
        this(name, quantity, BigDecimal.valueOf(amount));
    }
}
