package ru.zaytsv.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.zaytsv.MoyNalogClient;
import ru.zaytsv.config.MoyNalogClientConfig;
import ru.zaytsv.properties.MoyNalogProperties;

/**
 * Автоконфигурация Spring Boot для клиента «Мой налог».
 *
 * <p>Создаёт и регистрирует бин {@link MoyNalogClient} автоматически,
 * если в {@code application.properties} указаны {@code moy-nalog.username}
 * и {@code moy-nalog.password}.</p>
 *
 * <p>Если вам нужен полный контроль над созданием клиента, определите
 * собственный бин {@code MoyNalogClient} — автоконфигурация отступит.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(MoyNalogProperties.class)
@ConditionalOnProperty(prefix = "moy-nalog", name = {"username", "password"})
public class MoyNalogAutoConfiguration {

    /**
     * Создаёт и инициализирует {@link MoyNalogClient} на основе настроек
     * из {@code application.properties}.
     *
     * <p>Аутентификация выполняется при старте приложения.
     * Если указаны неверные учётные данные — приложение не запустится.</p>
     *
     * @param properties настройки, считанные из {@code application.properties}
     * @return готовый к использованию клиент
     */
    @Bean
    @ConditionalOnMissingBean
    public MoyNalogClient moyNalogClient(MoyNalogProperties properties) {
        MoyNalogClientConfig config = new MoyNalogClientConfig();
        config.setPrefix(properties.getPrefix());
        config.setApiPath(properties.getApiPath());
        config.setZoneOffset(properties.getZoneOffset());

        MoyNalogClient client = new MoyNalogClient(config);
        client.init(properties.getUsername(), properties.getPassword());
        return client;
    }
}
