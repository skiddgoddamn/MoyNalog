package ru.zaytsv.model;

/**
 * Квитанция, подтверждающая успешную регистрацию чека в ФНС.
 *
 * @param uuid     уникальный идентификатор зарегистрированного чека
 * @param jsonUrl  ссылка на данные чека в формате JSON
 * @param printUrl ссылка на версию чека для печати
 */
public record Receipt(String uuid, String jsonUrl, String printUrl) {
}
