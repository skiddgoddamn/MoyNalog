package ru.zaytsv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.zaytsv.config.MoyNalogClientConfig;
import ru.zaytsv.dto.AuthenticationDTO;
import ru.zaytsv.dto.RefreshTokenDTO;
import ru.zaytsv.exception.ApiException;
import ru.zaytsv.exception.ApiRequestException;
import ru.zaytsv.model.IncomeItem;
import ru.zaytsv.model.Receipt;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;

/**
 * HTTP-клиент для работы с API сервиса «Мой налог» (lknpd.nalog.ru).
 *
 * <p>Поддерживает аутентификацию по логину и паролю, автоматическое обновление
 * токена, синхронную и асинхронную отправку чеков.</p>
 *
 * <p>Пример использования без Spring:</p>
 * <pre>{@code
 * MoyNalogClient client = new MoyNalogClient();
 * client.init("username", "password");
 * Receipt receipt = client.addIncome(List.of(new Service("Консультация", 1, 5000.0)));
 * }</pre>
 *
 * <p>При использовании Spring Boot достаточно добавить в {@code application.properties}:</p>
 * <pre>
 * moy-nalog.username=ваш_логин
 * moy-nalog.password=ваш_пароль
 * </pre>
 */
public class MoyNalogClient {

    private static final Logger log = LoggerFactory.getLogger(MoyNalogClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private final ReadWriteLock refreshTokenLock = new ReentrantReadWriteLock(true);

    private final MoyNalogClientConfig clientConfig;

    /** Идентификатор устройства, генерируется один раз при создании клиента. */
    @Getter
    private final String deviceId;

    private String refreshToken;
    private String token;
    private String tokenExpireIn;

    private String inn;

    /**
     * Создаёт клиент с настройками по умолчанию.
     */
    public MoyNalogClient() {
        this(new MoyNalogClientConfig());
    }

    /**
     * Создаёт клиент с пользовательской конфигурацией.
     *
     * @param clientConfig конфигурация клиента
     */
    public MoyNalogClient(MoyNalogClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        this.deviceId = generateDeviceId(this.clientConfig.getPrefix());
    }

    /**
     * Инициализирует клиент: выполняет аутентификацию и сохраняет токены.
     *
     * <p>Должен быть вызван перед первым обращением к {@link #addIncome}.
     * При использовании Spring Boot автоконфигурации вызывается автоматически.</p>
     *
     * @param username логин пользователя
     * @param password пароль пользователя
     * @return профиль авторизованного пользователя
     * @throws ApiRequestException если сервер вернул ошибку аутентификации
     */
    public AuthenticationDTO.Profile init(String username, String password) {
        AuthenticationDTO authenticate = authenticate(username, password);
        this.refreshToken = authenticate.getRefreshToken();
        this.token = authenticate.getToken();
        this.tokenExpireIn = authenticate.getTokenExpireIn();
        this.inn = authenticate.getProfile().getInn();
        log.info("Пользователь {} успешно аутентифицирован в {}", this.inn, clientConfig.getApiPath());
        return authenticate.getProfile();
    }

    /**
     * Отправляет чек асинхронно, не блокируя вызывающий поток.
     *
     * @param services список оказанных услуг
     * @return {@link CompletableFuture} с результирующей квитанцией
     * @see #addIncome(List)
     */
    public CompletableFuture<Receipt> addIncomeAsync(List<IncomeItem> services) {
        return CompletableFuture.supplyAsync(() -> addIncome(services));
    }

    /**
     * Отправляет чек в ФНС и возвращает квитанцию с подтверждением.
     *
     * <p>Перед отправкой автоматически проверяет срок действия токена
     * и при необходимости обновляет его. Метод потокобезопасен.</p>
     *
     * @param services список оказанных услуг
     * @return квитанция с UUID и ссылками для просмотра и печати
     * @throws IllegalStateException если клиент не был инициализирован через {@link #init}
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public Receipt addIncome(List<IncomeItem> services) {
        checkToken();

        String operationTime = OffsetDateTime.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .withOffsetSameInstant(ZoneOffset.of(clientConfig.getZoneOffset()))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentType", "CASH");
        payload.put("ignoreMaxTotalIncomeRestriction", false);
        ObjectNode clientNode = payload.putObject("client");
        clientNode.putNull("contactPhone");
        clientNode.putNull("displayName");
        clientNode.putNull("inn");
        clientNode.put("incomeType", "FROM_INDIVIDUAL");
        payload.put("operationTime", operationTime);
        payload.put("requestTime", operationTime);

        ArrayNode servicesNode = payload.putArray("services");
        double totalAmount = 0;
        for (IncomeItem service : services) {
            double serviceTotalAmount = service.quantity() * service.amount();
            ObjectNode serviceNode = servicesNode.addObject();
            serviceNode.put("name", service.name());
            serviceNode.put("quantity", service.quantity());
            serviceNode.put("amount", BigDecimal.valueOf(serviceTotalAmount).setScale(2, RoundingMode.HALF_UP));
            totalAmount += serviceTotalAmount;
        }
        payload.put("totalAmount", BigDecimal.valueOf(totalAmount).setScale(2, RoundingMode.HALF_UP));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(clientConfig.getApiPath() + "/income"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .headers(getCommonHeaders())
                .header(clientConfig.getRefererHeader(), "https://lknpd.nalog.ru/sales/create")
                .header("Authorization", "Bearer " + token)
                .build();

        refreshTokenLock.readLock().lock();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            String body = response.body();
            if (statusCode != 200) {
                throw new ApiRequestException("ошибка отправки чека", statusCode, body);
            }
            String approvedReceiptUuid = MAPPER.readTree(body).path("approvedReceiptUuid").asText();
            final String jsonUrl = String.format("%s/receipt/%s/%s/json", clientConfig.getApiPath(), inn, approvedReceiptUuid);
            final String printUrl = String.format("%s/receipt/%s/%s/print", clientConfig.getApiPath(), inn, approvedReceiptUuid);
            return new Receipt(approvedReceiptUuid, jsonUrl, printUrl);
        } catch (IOException e) {
            throw new ApiException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(e.getMessage());
        } finally {
            refreshTokenLock.readLock().unlock();
        }
    }

    private AuthenticationDTO authenticate(String username, String password) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("username", username);
        payload.put("password", password);
        payload.set("deviceInfo", getDeviceInfo(deviceId));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(clientConfig.getApiPath() + "/auth/lkfl"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .headers(getCommonHeaders())
                .header(clientConfig.getRefererHeader(), "https://lknpd.nalog.ru/auth/login")
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            String body = response.body();
            if (statusCode != 200) {
                throw new ApiRequestException("ошибка аутентификации", statusCode, body);
            }
            return MAPPER.readValue(body, AuthenticationDTO.class);
        } catch (IOException e) {
            throw new ApiException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(e.getMessage());
        }
    }

    private RefreshTokenDTO refreshToken() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("deviceInfo", getDeviceInfo(deviceId));
        payload.put("refreshToken", refreshToken);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(clientConfig.getApiPath() + "/auth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .headers(getCommonHeaders())
                .header(clientConfig.getRefererHeader(), "https://lknpd.nalog.ru/sales")
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            String body = response.body();
            if (statusCode != 200) {
                throw new ApiRequestException("ошибка обновления токена", statusCode, body);
            }
            return MAPPER.readValue(body, RefreshTokenDTO.class);
        } catch (IOException e) {
            throw new ApiException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(e.getMessage());
        }
    }

    private void checkToken() {
        if (token == null) {
            throw new IllegalStateException("Клиент не инициализирован — вызовите init(username, password)");
        }
        OffsetDateTime now = OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime tokenExpireDatetime = OffsetDateTime.parse(tokenExpireIn);
        if (now.isAfter(tokenExpireDatetime)) {
            refreshTokenLock.writeLock().lock();
            try {
                RefreshTokenDTO refreshTokenDTO = refreshToken();
                this.refreshToken = refreshTokenDTO.getRefreshToken();
                this.token = refreshTokenDTO.getToken();
                this.tokenExpireIn = refreshTokenDTO.getTokenExpireIn();
                log.debug("Токен успешно обновлён");
            } finally {
                refreshTokenLock.writeLock().unlock();
            }
        } else {
            log.trace("Токен действителен");
        }
    }

    private String generateDeviceId(String prefix) {
        SecureRandom random = new SecureRandom();
        final String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder deviceInfoBuilder = new StringBuilder(prefix);
        IntStream.range(0, 21 - prefix.length()).forEach(v -> {
            int index = random.nextInt(chars.length());
            deviceInfoBuilder.append(chars.charAt(index));
        });
        return deviceInfoBuilder.toString();
    }

    private ObjectNode getDeviceInfo(String deviceId) {
        ObjectNode deviceInfoNode = MAPPER.createObjectNode();
        deviceInfoNode.put("appVersion", "1.0.0");
        deviceInfoNode.put("sourceType", "WEB");
        deviceInfoNode.put("sourceDeviceId", deviceId);
        ObjectNode metaDetailsNode = deviceInfoNode.putObject("metaDetails");
        metaDetailsNode.put("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0");
        return deviceInfoNode;
    }

    private String[] getCommonHeaders() {
        return new String[]{
                "Accept", "application/json, text/plain, */*",
                "Accept-Language", "ru,en;q=0.9",
                "Content-Type", "application/json"
        };
    }
}
