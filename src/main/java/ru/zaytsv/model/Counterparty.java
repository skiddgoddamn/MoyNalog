package ru.zaytsv.model;

import lombok.Getter;

/**
 * Контрагент (плательщик), которому регистрируется доход в чеке.
 *
 * <p>Для дохода от физлица используйте {@link #individual()} — это поведение по умолчанию
 * метода {@code addIncome(services)}. Для юрлица/ИП или иностранной организации укажите ИНН
 * и наименование.</p>
 */
@Getter
public class Counterparty {

    /** Тип плательщика (соответствует полю {@code incomeType} в API). */
    private final ClientType type;

    /** ИНН плательщика. Может быть {@code null} для физлица. */
    private final String inn;

    /** Наименование плательщика. Может быть {@code null} для анонимного физлица. */
    private final String displayName;

    /** Контактный телефон плательщика. Необязателен. */
    private final String contactPhone;

    public Counterparty(ClientType type, String inn, String displayName, String contactPhone) {
        this.type = type;
        this.inn = inn;
        this.displayName = displayName;
        this.contactPhone = contactPhone;
    }

    /** Анонимное физлицо (доход без указания плательщика). */
    public static Counterparty individual() {
        return new Counterparty(ClientType.FROM_INDIVIDUAL, null, null, null);
    }

    /** Юридическое лицо или ИП. */
    public static Counterparty legalEntity(String inn, String displayName) {
        return new Counterparty(ClientType.FROM_LEGAL_ENTITY, inn, displayName, null);
    }

    /** Иностранная организация. */
    public static Counterparty foreignAgency(String displayName) {
        return new Counterparty(ClientType.FROM_FOREIGN_AGENCY, null, displayName, null);
    }
}
