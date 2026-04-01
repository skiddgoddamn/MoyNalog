package ru.zaytsv.exception;

/**
 * Базовое исключение клиента «Мой налог».
 *
 * <p>Выбрасывается при сетевых ошибках, прерывании потока и других
 * нештатных ситуациях, не связанных с HTTP-кодом ответа сервера.
 * Для ошибок на уровне HTTP см. {@link ApiRequestException}.</p>
 */
public class ApiException extends RuntimeException {

    /**
     * @param message описание ошибки
     */
    public ApiException(String message) {
        super(message);
    }
}
