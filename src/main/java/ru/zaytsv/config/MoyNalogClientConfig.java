package ru.zaytsv.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

/**
 * Конфигурация клиента {@code MoyNalogClient}.
 *
 * <p>Используется при создании клиента вручную (без Spring).
 * При использовании Spring Boot автоконфигурации настройки задаются
 * через {@code application.properties} с префиксом {@code moy-nalog}.</p>
 *
 * <p>Пример:</p>
 * <pre>{@code
 * MoyNalogClientConfig config = new MoyNalogClientConfig();
 * config.setZoneOffset("+03:00");
 * config.setProxyHost("proxy.example.com");
 * config.setProxyPort(8080);
 * MoyNalogClient client = new MoyNalogClient(config);
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class MoyNalogClientConfig {

    /** Префикс для генерируемого идентификатора устройства. По умолчанию пустой. */
    @NonNull
    private String prefix = "";

    /** Базовый URL API сервиса «Мой налог». */
    @NonNull
    private String apiPath = "https://lknpd.nalog.ru/api/v1";

    /**
     * Смещение часового пояса для временных меток чека.
     * Значение в формате ISO-8601, например {@code +03:00} или {@code Z} (UTC).
     */
    @NonNull
    private String zoneOffset = "Z";

    /** Название HTTP-заголовка Referer. Изменять не требуется. */
    @NonNull
    private String refererHeader = "Referer";

    /**
     * Таймаут ожидания ответа от сервера (секунды).
     * Применяется к каждому HTTP-запросу. По умолчанию 30 секунд.
     */
    private int requestTimeout = 30;

    /** Хост прокси-сервера. Если {@code null} или пустой — прокси не используется. */
    private String proxyHost;

    /** Порт прокси-сервера. По умолчанию 8080. */
    private int proxyPort = 8080;

    /** Логин для аутентификации на прокси. Необязателен. */
    private String proxyUsername;

    /** Пароль для аутентификации на прокси. Необязателен. */
    private String proxyPassword;
}
