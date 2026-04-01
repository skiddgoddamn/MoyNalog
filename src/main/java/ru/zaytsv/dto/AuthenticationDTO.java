package ru.zaytsv.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Ответ API на запрос аутентификации.
 *
 * <p>Содержит токены доступа и данные профиля пользователя.
 * Используется внутри библиотеки; из публичного API доступен только {@link Profile}
 * — он возвращается методом {@code MoyNalogClient.init()}.</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthenticationDTO {

    private String refreshToken;
    private String refreshTokenExpiresIn;
    private String token;
    private String tokenExpireIn;
    private Profile profile;

    /**
     * Профиль пользователя сервиса «Мой налог».
     *
     * <p>Поля могут быть {@code null}, если не заполнены в личном кабинете.</p>
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        private String lastName;
        private Long id;
        /** Имя для отображения (firstName из API). */
        private String displayName;
        private String middleName;
        private String email;
        private String phone;
        /** ИНН — индивидуальный номер налогоплательщика. */
        private String inn;
        private String snils;
        private Boolean avatarExists;
        private String initialRegistrationDate;
        private String registrationDate;
        private String firstReceiptRegisterTime;
        private String firstReceiptCancelTime;
        private Boolean hideCancelledReceipt;
        private String registerAvailable;
        private String status;
        private Boolean restrictedMode;
        private String pfrUrl;
        private String login;
    }
}
