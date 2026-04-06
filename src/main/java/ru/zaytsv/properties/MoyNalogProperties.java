package ru.zaytsv.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки клиента «Мой налог», задаваемые через {@code application.properties}.
 *
 * <p>Пример конфигурации:</p>
 * <pre>
 * moy-nalog.username=ваш_логин
 * moy-nalog.password=ваш_пароль
 * moy-nalog.zone-offset=+03:00
 * moy-nalog.proxy.host=proxy.example.com
 * moy-nalog.proxy.port=8080
 * moy-nalog.proxy.username=proxyuser
 * moy-nalog.proxy.password=proxypass
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "moy-nalog")
public class MoyNalogProperties {

    /** Логин пользователя в сервисе «Мой налог». */
    private String username;

    /** Пароль пользователя в сервисе «Мой налог». */
    private String password;

    /**
     * Префикс для идентификатора устройства.
     * Необязателен, по умолчанию пустой.
     */
    private String prefix = "";

    /**
     * Базовый URL API. Менять не нужно при работе с боевым сервисом.
     */
    private String apiPath = "https://lknpd.nalog.ru/api/v1";

    /**
     * Смещение часового пояса для временных меток чека.
     * Формат ISO-8601: {@code +03:00}, {@code +05:00}, {@code Z} (UTC).
     * По умолчанию UTC.
     */
    private String zoneOffset = "Z";

    /**
     * Таймаут ожидания ответа от сервера (секунды).
     * По умолчанию 30 секунд.
     */
    private int requestTimeout = 30;

    /** Настройки прокси-сервера. Если {@code host} не задан — прокси не используется. */
    private Proxy proxy = new Proxy();

    @Getter
    @Setter
    public static class Proxy {
        /** Хост прокси-сервера. */
        private String host;
        /** Порт прокси-сервера. По умолчанию 8080. */
        private int port = 8080;
        /** Логин для аутентификации на прокси. Необязателен. */
        private String username;
        /** Пароль для аутентификации на прокси. Необязателен. */
        private String password;
    }
}
