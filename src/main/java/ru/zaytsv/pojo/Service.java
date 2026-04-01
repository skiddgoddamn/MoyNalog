package ru.zaytsv.pojo;

/**
 * Оказанная услуга, включаемая в чек.
 *
 * <p><b>Важно:</b> {@code amount} — цена за единицу услуги, а не итоговая сумма.
 * Итог рассчитывается автоматически: {@code amount × quantity}.</p>
 *
 * @param name     наименование услуги
 * @param quantity количество единиц
 * @param amount   цена за одну единицу (в рублях)
 */
public record Service(String name, int quantity, double amount) {
}
