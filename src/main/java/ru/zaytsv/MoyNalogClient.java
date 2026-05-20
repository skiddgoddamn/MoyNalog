package ru.zaytsv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.zaytsv.config.MoyNalogClientConfig;
import ru.zaytsv.dto.AuthenticationDTO;
import ru.zaytsv.dto.RefreshTokenDTO;
import ru.zaytsv.exception.ApiException;
import ru.zaytsv.exception.ApiRequestException;
import ru.zaytsv.model.ClientType;
import ru.zaytsv.model.IncomeItem;
import ru.zaytsv.model.Invoice;
import ru.zaytsv.model.PaymentType;
import ru.zaytsv.model.Receipt;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * <p>Под капотом — Apache HttpClient5. Раньше использовался {@code java.net.http.HttpClient}
 * с установленным {@link java.net.Authenticator} (для proxy CONNECT). JDK после этого
 * добавлял {@code AuthenticationFilter}, который на ЛЮБОЙ 401-ответ (включая ответы
 * от target-сервера, не только от прокси) строго требовал заголовок {@code WWW-Authenticate}
 * и при его отсутствии бросал синтетическое {@code IOException("WWW-Authenticate header
 * missing for response code 401")}. Реальное тело ошибки от nalog.ru при этом терялось,
 * клиент думал что токен протух, и крутил бесполезные reinit'ы. Apache HttpClient5
 * различает proxy/target через {@code AuthScope} и спокойно возвращает 401 от target
 * как обычный response с body — реальная причина отказа от ФНС видна в
 * {@link ApiRequestException#getBody()}.</p>
 *
 * <p>Пример использования без Spring:</p>
 * <pre>{@code
 * MoyNalogClient client = new MoyNalogClient();
 * client.init("username", "password");
 * Receipt receipt = client.addIncome(List.of(new IncomeItem("Консультация", 1, 5000.0)));
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

    private final CloseableHttpClient httpClient;

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
        this.httpClient = buildHttpClient();
    }

    private CloseableHttpClient buildHttpClient() {
        var builder = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(15))
                        .setResponseTimeout(Timeout.ofSeconds(Math.max(15, clientConfig.getRequestTimeout())))
                        .build());

        String proxyHost = clientConfig.getProxyHost();
        if (proxyHost != null && !proxyHost.isEmpty()) {
            HttpHost proxy = new HttpHost("http", proxyHost, clientConfig.getProxyPort());
            builder.setProxy(proxy);

            String proxyUsername = clientConfig.getProxyUsername();
            if (proxyUsername != null && !proxyUsername.isEmpty()) {
                String proxyPassword = clientConfig.getProxyPassword();
                BasicCredentialsProvider creds = new BasicCredentialsProvider();
                // AuthScope, привязанный к HttpHost прокси, — Apache не будет использовать
                // эти учётки для target-сервера; для CONNECT-туннеля сам пошлёт Proxy-Authorization.
                creds.setCredentials(
                        new AuthScope(proxy),
                        new UsernamePasswordCredentials(proxyUsername,
                                proxyPassword == null ? new char[0] : proxyPassword.toCharArray())
                );
                builder.setDefaultCredentialsProvider(creds);
            }
        }
        return builder.build();
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

        HttpPost req = new HttpPost(clientConfig.getApiPath() + "/income");
        req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");

        refreshTokenLock.readLock().lock();
        try {
            HttpExchange resp = execute(req, "ошибка отправки чека");
            String approvedReceiptUuid = MAPPER.readTree(resp.body).path("approvedReceiptUuid").asText();
            final String jsonUrl = String.format("%s/receipt/%s/%s/json", clientConfig.getApiPath(), inn, approvedReceiptUuid);
            final String printUrl = String.format("%s/receipt/%s/%s/print", clientConfig.getApiPath(), inn, approvedReceiptUuid);
            return new Receipt(approvedReceiptUuid, jsonUrl, printUrl);
        } catch (IOException e) {
            throw new ApiException(e.getMessage());
        } finally {
            refreshTokenLock.readLock().unlock();
        }
    }

    /**
     * Возвращает список сохранённых реквизитов (способов оплаты) из личного кабинета.
     *
     * <p>Используется для получения банковских реквизитов перед выставлением счёта.</p>
     *
     * @param favoriteOnly {@code true} — только избранные реквизиты
     * @return список реквизитов
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public List<PaymentType> getPaymentTypes(boolean favoriteOnly) {
        checkToken();

        String url = clientConfig.getApiPath() + "/payment-type/table" + (favoriteOnly ? "?favorite=true" : "");
        HttpGet req = new HttpGet(url);
        applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");

        refreshTokenLock.readLock().lock();
        try {
            HttpExchange resp = execute(req, "ошибка получения реквизитов");
            JsonNode root = MAPPER.readTree(resp.body);
            JsonNode items = root.path("items");
            List<PaymentType> result = new ArrayList<>();
            for (JsonNode item : items) {
                result.add(MAPPER.treeToValue(item, PaymentType.class));
            }
            return result;
        } catch (IOException e) {
            throw new ApiException(e.getMessage());
        } finally {
            refreshTokenLock.readLock().unlock();
        }
    }

    /**
     * Выставляет счёт через API сервиса «Мой налог».
     *
     * <p>Пример использования:</p>
     * <pre>{@code
     * List<PaymentType> types = client.getPaymentTypes(true);
     * PaymentType account = types.get(0);
     * Invoice invoice = client.createInvoice(
     *     account,
     *     "ИП Иванов Иван Иванович", "123456789012", ClientType.FROM_LEGAL_ENTITY,
     *     List.of(new IncomeItem("Консультация", 1, 5000.0))
     * );
     * System.out.println(invoice.getTransitionPageURL());
     * }</pre>
     *
     * @param paymentType реквизиты оплаты (полученные через {@link #getPaymentTypes})
     * @param clientName  наименование плательщика
     * @param clientInn   ИНН плательщика
     * @param clientType  тип плательщика
     * @param services    список услуг
     * @return выставленный счёт
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public Invoice createInvoice(PaymentType paymentType,
                                 String clientName, String clientInn, ClientType clientType,
                                 List<IncomeItem> services) {
        checkToken();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentType", paymentType.getType());
        payload.put("bankName", paymentType.getBankName());
        payload.put("bankBik", paymentType.getBankBik());
        payload.put("corrAccount", paymentType.getCorrAccount());
        payload.put("currentAccount", paymentType.getCurrentAccount());
        payload.put("clientName", clientName);
        payload.put("clientInn", clientInn);
        payload.put("type", "MANUAL");
        payload.put("clientType", clientType.getValue());

        ArrayNode servicesNode = payload.putArray("services");
        double totalAmount = 0;
        for (int i = 0; i < services.size(); i++) {
            IncomeItem item = services.get(i);
            double itemTotal = item.quantity() * item.amount();
            ObjectNode serviceNode = servicesNode.addObject();
            serviceNode.put("name", item.name());
            serviceNode.put("amount", BigDecimal.valueOf(item.amount()).setScale(2, RoundingMode.HALF_UP));
            serviceNode.put("quantity", item.quantity());
            serviceNode.put("serviceNumber", i);
            totalAmount += itemTotal;
        }
        payload.put("totalAmount", BigDecimal.valueOf(totalAmount).setScale(2, RoundingMode.HALF_UP).toPlainString());

        HttpPost req = new HttpPost(clientConfig.getApiPath() + "/invoice");
        req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");

        refreshTokenLock.readLock().lock();
        try {
            HttpExchange resp = execute(req, "ошибка создания счёта");
            return MAPPER.readValue(resp.body, Invoice.class);
        } catch (IOException e) {
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

        IOException lastException = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpPost req = new HttpPost(clientConfig.getApiPath() + "/auth/lkfl");
                req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
                applyCommonHeadersNoBearer(req, "https://lknpd.nalog.ru/auth/login");

                HttpExchange resp = execute(req, "ошибка аутентификации");
                return MAPPER.readValue(resp.body, AuthenticationDTO.class);
            } catch (ApiRequestException e) {
                throw e;
            } catch (IOException e) {
                lastException = e;
                log.warn("Попытка аутентификации {} не удалась: {}", attempt + 1, e.getMessage());
            }
        }
        throw new ApiException(lastException == null ? "unknown auth error" : lastException.getMessage());
    }

    private RefreshTokenDTO refreshToken() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("deviceInfo", getDeviceInfo(deviceId));
        payload.put("refreshToken", refreshToken);

        HttpPost req = new HttpPost(clientConfig.getApiPath() + "/auth/token");
        req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        applyCommonHeadersNoBearer(req, "https://lknpd.nalog.ru/sales");

        try {
            HttpExchange resp = execute(req, "ошибка обновления токена");
            return MAPPER.readValue(resp.body, RefreshTokenDTO.class);
        } catch (IOException e) {
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

    private void applyCommonHeaders(HttpUriRequestBase req, String referer) {
        applyCommonHeadersNoBearer(req, referer);
        req.setHeader("Authorization", "Bearer " + token);
    }

    private void applyCommonHeadersNoBearer(HttpUriRequestBase req, String referer) {
        req.setHeader("Accept", "application/json, text/plain, */*");
        req.setHeader("Accept-Language", "ru,en;q=0.9");
        // Content-Type выставляется StringEntity для POST'ов; для GET — не нужен
        req.setHeader(clientConfig.getRefererHeader(), referer);
    }

    private HttpExchange execute(HttpUriRequestBase req, String errorMessage) throws IOException {
        return httpClient.execute(req, resp -> {
            int status = resp.getCode();
            String body = resp.getEntity() != null ? EntityUtils.toString(resp.getEntity()) : "";
            if (status != 200) {
                throw new ApiRequestException(errorMessage, status, body);
            }
            return new HttpExchange(status, body);
        });
    }

    /** Простой DTO для пары status+body, чтобы передать через лямбду {@link CloseableHttpClient#execute}. */
    private record HttpExchange(int status, String body) {}
}
