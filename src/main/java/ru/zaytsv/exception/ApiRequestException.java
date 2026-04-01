package ru.zaytsv.exception;

import lombok.Getter;

/**
 * Исключение, возникающее при получении от API ответа с кодом, отличным от 200.
 *
 * <p>Содержит HTTP-статус и тело ответа для диагностики проблемы.</p>
 */
@Getter
public class ApiRequestException extends ApiException {

    /** HTTP-код ответа сервера. */
    private final int statusCode;

    /** Тело ответа сервера. */
    private final transient Object body;

    /**
     * @param message    краткое описание операции, при которой произошла ошибка
     * @param statusCode HTTP-код ответа
     * @param body       тело ответа
     */
    public ApiRequestException(String message, int statusCode, Object body) {
        super(String.format("Ошибка запроса к API: %s. Код ответа: %d. Тело: %s", message, statusCode, body));
        this.statusCode = statusCode;
        this.body = body;
    }
}
