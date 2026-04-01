package ru.zaytsv.model;

/**
 * Позиция (услуга/товар), включаемая в чек.
 *
 * <p><b>Важно:</b> {@code amount} — цена за единицу, а не итоговая сумма.
 * Итог рассчитывается автоматически: {@code amount × quantity}.</p>
 *
 * @param name     наименование услуги или товара
 * @param quantity количество единиц
 * @param amount   цена за одну единицу (в рублях)
 */
public record IncomeItem(String name, int quantity, double amount) {
}
