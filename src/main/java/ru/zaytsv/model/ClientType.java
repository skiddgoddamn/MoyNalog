package ru.zaytsv.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Тип плательщика при выставлении счёта.
 */
public enum ClientType {

    /** Юридическое лицо или индивидуальный предприниматель. */
    FROM_LEGAL_ENTITY("FROM_LEGAL_ENTITY"),

    /** Физическое лицо. */
    FROM_INDIVIDUAL("FROM_INDIVIDUAL");

    private final String value;

    ClientType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
