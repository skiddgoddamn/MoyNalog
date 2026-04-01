package ru.zaytsv.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Ответ API на запрос обновления токена доступа.
 *
 * <p>Используется внутри библиотеки для прозрачного обновления токена
 * без повторной аутентификации пользователя.</p>
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RefreshTokenDTO {
    private String refreshToken;
    private String refreshTokenExpiresIn;
    private String token;
    private String tokenExpireIn;
}
