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
}
