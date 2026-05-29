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
import ru.zaytsv.model.CancelReason;
import ru.zaytsv.model.ClientType;
import ru.zaytsv.model.Counterparty;
import ru.zaytsv.model.IncomeItem;
import ru.zaytsv.model.IncomesPage;
import ru.zaytsv.model.IncomesQuery;
import ru.zaytsv.model.Invoice;
import ru.zaytsv.model.PaymentType;
import ru.zaytsv.model.Receipt;
import ru.zaytsv.model.TaxInfo;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * HTTP-клиент для работы с API сервиса «Мой налог» (lknpd.nalog.ru).
 *
 * <p>Поддерживает аутентификацию по логину и паролю, автоматическое обновление
 * токена (в т.ч. принудительное при 401), синхронную и асинхронную отправку чеков,
 * аннулирование чеков, выставление и отмену счетов, чтение истории доходов, профиля
 * и сводки по налогу.</p>
 *
 * <p>Под капотом — Apache HttpClient5. Раньше использовался {@code java.net.http.HttpClient}
 * с установленным {@link java.net.Authenticator} (для proxy CONNECT). JDK после этого
 * добавлял {@code AuthenticationFilter}, который на ЛЮБОЙ 401-ответ (включая ответы
 * от target-сервера, не только от прокси) строго требовал заголовок {@code WWW-Authenticate}
 * и при его отсутствии бросал синтетическое {@code IOException}. Реальное тело ошибки от
 * nalog.ru при этом терялось. Apache HttpClient5 различает proxy/target через {@code AuthScope}
 * и спокойно возвращает 401 от target как обычный response с body — реальная причина отказа
 * от ФНС видна в {@link ApiRequestException#getBody()}.</p>
 *
 * <p>Клиент потокобезопасен и реализует {@link AutoCloseable} — закрывайте его (или используйте
 * try-with-resources / Spring-бин с {@code destroyMethod}), чтобы освободить пул соединений.</p>
 *
 * <p>Пример использования без Spring:</p>
 * <pre>{@code
 * try (MoyNalogClient client = new MoyNalogClient()) {
 *     client.init("username", "password");
 *     Receipt receipt = client.addIncome(List.of(new IncomeItem("Консультация", 1, 5000.0)));
 * }
 * }</pre>
 *
 * <p>При использовании Spring Boot достаточно добавить в {@code application.properties}:</p>
 * <pre>
 * moy-nalog.username=ваш_логин
 * moy-nalog.password=ваш_пароль
 * </pre>
 */
public class MoyNalogClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MoyNalogClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** За сколько секунд до фактического истечения токен считается «протухающим» и обновляется. */
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;

    private final CloseableHttpClient httpClient;

    private final ReadWriteLock refreshTokenLock = new ReentrantReadWriteLock(true);

    private final MoyNalogClientConfig clientConfig;

    /** Идентификатор устройства, генерируется один раз при создании клиента. */
    @Getter
    private final String deviceId;

    private volatile String refreshToken;
    private volatile String token;
    private volatile String tokenExpireIn;

    private volatile String inn;

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
     * <p>Должен быть вызван перед первым обращением к остальным методам.
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

    // ------------------------------------------------------------------ доходы

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
     * Отправляет чек на доход от физлица (анонимный плательщик).
     *
     * @param services список оказанных услуг
     * @return квитанция с UUID и ссылками для просмотра и печати
     * @throws IllegalStateException    если клиент не был инициализирован через {@link #init}
     * @throws IllegalArgumentException если список услуг пуст
     * @throws ApiRequestException      если сервер вернул код ответа, отличный от 200
     */
    public Receipt addIncome(List<IncomeItem> services) {
        return addIncome(services, Counterparty.individual());
    }

    /**
     * Отправляет чек с указанием контрагента (физлицо, юрлицо/ИП или иностранная организация).
     *
     * @param services список оказанных услуг
     * @param client   плательщик
     * @return квитанция с UUID и ссылками для просмотра и печати
     * @throws IllegalStateException    если клиент не был инициализирован
     * @throws IllegalArgumentException если список услуг пуст
     * @throws ApiRequestException      если сервер вернул код ответа, отличный от 200
     */
    public Receipt addIncome(List<IncomeItem> services, Counterparty client) {
        requireNonEmpty(services);
        HttpExchange resp = sendAuthorized(() -> buildIncomeRequest(services, client), "ошибка отправки чека");
        try {
            String uuid = MAPPER.readTree(resp.body()).path("approvedReceiptUuid").asText();
            String jsonUrl = String.format("%s/receipt/%s/%s/json", clientConfig.getApiPath(), inn, uuid);
            String printUrl = String.format("%s/receipt/%s/%s/print", clientConfig.getApiPath(), inn, uuid);
            return new Receipt(uuid, jsonUrl, printUrl);
        } catch (IOException e) {
            throw new ApiException("ошибка разбора ответа чека", e);
        }
    }

    private HttpPost buildIncomeRequest(List<IncomeItem> services, Counterparty client) {
        String operationTime = nowFormatted();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentType", "CASH");
        payload.put("ignoreMaxTotalIncomeRestriction", false);
        ObjectNode clientNode = payload.putObject("client");
        clientNode.put("contactPhone", client.getContactPhone());
        clientNode.put("displayName", client.getDisplayName());
        clientNode.put("inn", client.getInn());
        clientNode.put("incomeType", client.getType().getValue());
        payload.put("operationTime", operationTime);
        payload.put("requestTime", operationTime);

        ArrayNode servicesNode = payload.putArray("services");
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (IncomeItem service : services) {
            BigDecimal lineTotal = service.amount()
                    .multiply(BigDecimal.valueOf(service.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            ObjectNode serviceNode = servicesNode.addObject();
            serviceNode.put("name", service.name());
            serviceNode.put("quantity", service.quantity());
            serviceNode.put("amount", lineTotal);
            totalAmount = totalAmount.add(lineTotal);
        }
        payload.put("totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP));

        HttpPost req = new HttpPost(clientConfig.getApiPath() + "/income");
        req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");
        return req;
    }

    /**
     * Аннулирует ранее зарегистрированный чек.
     *
     * @param receiptUuid UUID чека (см. {@link Receipt#uuid()})
     * @param reason      причина аннулирования
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public void cancelReceipt(String receiptUuid, CancelReason reason) {
        sendAuthorized(() -> {
            String time = nowFormatted();
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("operationTime", time);
            payload.put("requestTime", time);
            payload.put("comment", reason.getComment());
            payload.put("receiptUuid", receiptUuid);
            HttpPost req = new HttpPost(clientConfig.getApiPath() + "/cancel");
            req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
            applyCommonHeaders(req, "https://lknpd.nalog.ru/sales");
            return req;
        }, "ошибка аннулирования чека");
    }

    /**
     * Асинхронно аннулирует чек.
     *
     * @see #cancelReceipt(String, CancelReason)
     */
    public CompletableFuture<Void> cancelReceiptAsync(String receiptUuid, CancelReason reason) {
        return CompletableFuture.runAsync(() -> cancelReceipt(receiptUuid, reason));
    }

    /**
     * Возвращает историю доходов (зарегистрированных чеков) за период.
     *
     * @param query параметры выборки (период, лимит, смещение, сортировка)
     * @return страница истории доходов
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public IncomesPage getIncomes(IncomesQuery query) {
        HttpExchange resp = sendAuthorized(() -> {
            StringBuilder url = new StringBuilder(clientConfig.getApiPath()).append("/incomes?")
                    .append("offset=").append(query.getOffset())
                    .append("&sortBy=").append(urlEncode(query.getSortBy()))
                    .append("&limit=").append(query.getLimit());
            if (query.getFrom() != null) {
                url.append("&from=").append(urlEncode(formatDateTime(query.getFrom())));
            }
            if (query.getTo() != null) {
                url.append("&to=").append(urlEncode(formatDateTime(query.getTo())));
            }
            HttpGet req = new HttpGet(url.toString());
            applyCommonHeaders(req, "https://lknpd.nalog.ru/sales");
            return req;
        }, "ошибка получения истории доходов");
        try {
            return MAPPER.readValue(resp.body(), IncomesPage.class);
        } catch (IOException e) {
            throw new ApiException("ошибка разбора истории доходов", e);
        }
    }

    /**
     * Загружает JSON-представление зарегистрированного чека.
     *
     * @param receiptUuid UUID чека
     * @return тело JSON-чека как строка
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public String getReceiptJson(String receiptUuid) {
        HttpExchange resp = sendAuthorized(() -> {
            HttpGet req = new HttpGet(String.format("%s/receipt/%s/%s/json",
                    clientConfig.getApiPath(), inn, receiptUuid));
            applyCommonHeaders(req, "https://lknpd.nalog.ru/sales");
            return req;
        }, "ошибка получения чека");
        return resp.body();
    }

    // ----------------------------------------------------------------- профиль

    /**
     * Возвращает актуальный профиль пользователя ({@code GET /user}).
     *
     * @return профиль пользователя
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public AuthenticationDTO.Profile getUser() {
        HttpExchange resp = sendAuthorized(() -> {
            HttpGet req = new HttpGet(clientConfig.getApiPath() + "/user");
            applyCommonHeaders(req, "https://lknpd.nalog.ru/profile");
            return req;
        }, "ошибка получения профиля");
        try {
            return MAPPER.readValue(resp.body(), AuthenticationDTO.Profile.class);
        } catch (IOException e) {
            throw new ApiException("ошибка разбора профиля", e);
        }
    }

    /**
     * Возвращает сводку по налогу и начислениям ({@code GET /taxes}).
     *
     * @return сводка по налогу
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public TaxInfo getTaxes() {
        HttpExchange resp = sendAuthorized(() -> {
            HttpGet req = new HttpGet(clientConfig.getApiPath() + "/taxes");
            applyCommonHeaders(req, "https://lknpd.nalog.ru/taxes");
            return req;
        }, "ошибка получения сводки по налогу");
        try {
            return MAPPER.readValue(resp.body(), TaxInfo.class);
        } catch (IOException e) {
            throw new ApiException("ошибка разбора сводки по налогу", e);
        }
    }

    // ------------------------------------------------------------------- счета

    /**
     * Возвращает список сохранённых реквизитов (способов оплаты) из личного кабинета.
     *
     * @param favoriteOnly {@code true} — только избранные реквизиты
     * @return список реквизитов
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public List<PaymentType> getPaymentTypes(boolean favoriteOnly) {
        HttpExchange resp = sendAuthorized(() -> {
            String url = clientConfig.getApiPath() + "/payment-type/table" + (favoriteOnly ? "?favorite=true" : "");
            HttpGet req = new HttpGet(url);
            applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");
            return req;
        }, "ошибка получения реквизитов");
        try {
            JsonNode items = MAPPER.readTree(resp.body()).path("items");
            List<PaymentType> result = new ArrayList<>();
            for (JsonNode item : items) {
                result.add(MAPPER.treeToValue(item, PaymentType.class));
            }
            return result;
        } catch (IOException e) {
            throw new ApiException("ошибка разбора реквизитов", e);
        }
    }

    /**
     * Выставляет счёт юрлицу/ИП через API сервиса «Мой налог».
     *
     * @param paymentType реквизиты оплаты (полученные через {@link #getPaymentTypes})
     * @param clientName  наименование плательщика
     * @param clientInn   ИНН плательщика
     * @param clientType  тип плательщика
     * @param services    список услуг
     * @return выставленный счёт
     * @throws IllegalStateException    если клиент не был инициализирован
     * @throws IllegalArgumentException если список услуг пуст
     * @throws ApiRequestException      если сервер вернул код ответа, отличный от 200
     */
    public Invoice createInvoice(PaymentType paymentType,
                                 String clientName, String clientInn, ClientType clientType,
                                 List<IncomeItem> services) {
        requireNonEmpty(services);
        return sendInvoice(services, payload -> {
            applyAccountFields(payload, paymentType);
            payload.put("clientName", clientName);
            payload.put("clientInn", clientInn);
            payload.put("clientType", clientType.getValue());
        });
    }

    /**
     * Выставляет счёт физлицу с указанием телефона и email (без ИНН).
     *
     * @param paymentType реквизиты оплаты
     * @param clientName  имя плательщика
     * @param clientPhone телефон плательщика (необязателен)
     * @param clientEmail email плательщика (необязателен)
     * @param services    список услуг
     * @return выставленный счёт
     * @throws IllegalStateException    если клиент не был инициализирован
     * @throws IllegalArgumentException если список услуг пуст
     * @throws ApiRequestException      если сервер вернул код ответа, отличный от 200
     */
    public Invoice createInvoiceForIndividual(PaymentType paymentType,
                                              String clientName, String clientPhone, String clientEmail,
                                              List<IncomeItem> services) {
        requireNonEmpty(services);
        return sendInvoice(services, payload -> {
            applyAccountFields(payload, paymentType);
            payload.put("clientName", clientName);
            payload.put("clientPhone", clientPhone);
            payload.put("clientEmail", clientEmail);
            payload.put("clientType", ClientType.FROM_INDIVIDUAL.getValue());
        });
    }

    /**
     * Отменяет ранее выставленный счёт.
     *
     * @param invoiceId числовой идентификатор счёта ({@link Invoice#getInvoiceId()})
     * @return обновлённый счёт (или {@code null}, если сервер вернул пустой ответ)
     * @throws IllegalStateException если клиент не был инициализирован
     * @throws ApiRequestException   если сервер вернул код ответа, отличный от 200
     */
    public Invoice cancelInvoice(long invoiceId) {
        HttpExchange resp = sendAuthorized(() -> {
            HttpPost req = new HttpPost(clientConfig.getApiPath() + "/invoice/" + invoiceId + "/cancel");
            applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");
            return req;
        }, "ошибка отмены счёта");
        if (resp.body() == null || resp.body().isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(resp.body(), Invoice.class);
        } catch (IOException e) {
            throw new ApiException("ошибка разбора ответа отмены счёта", e);
        }
    }

    private Invoice sendInvoice(List<IncomeItem> services, java.util.function.Consumer<ObjectNode> clientFields) {
        HttpExchange resp = sendAuthorized(() -> {
            ObjectNode payload = MAPPER.createObjectNode();
            clientFields.accept(payload);
            payload.put("type", "MANUAL");

            ArrayNode servicesNode = payload.putArray("services");
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (int i = 0; i < services.size(); i++) {
                IncomeItem item = services.get(i);
                BigDecimal unit = item.amount().setScale(2, RoundingMode.HALF_UP);
                ObjectNode serviceNode = servicesNode.addObject();
                serviceNode.put("name", item.name());
                serviceNode.put("amount", unit);
                serviceNode.put("quantity", item.quantity());
                serviceNode.put("serviceNumber", i);
                totalAmount = totalAmount.add(unit.multiply(BigDecimal.valueOf(item.quantity())));
            }
            payload.put("totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());

            HttpPost req = new HttpPost(clientConfig.getApiPath() + "/invoice");
            req.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
            applyCommonHeaders(req, "https://lknpd.nalog.ru/sales/create");
            return req;
        }, "ошибка создания счёта");
        try {
            return MAPPER.readValue(resp.body(), Invoice.class);
        } catch (IOException e) {
            throw new ApiException("ошибка разбора ответа счёта", e);
        }
    }

    private void applyAccountFields(ObjectNode payload, PaymentType paymentType) {
        payload.put("paymentType", paymentType.getType());
        payload.put("bankName", paymentType.getBankName());
        payload.put("bankBik", paymentType.getBankBik());
        payload.put("corrAccount", paymentType.getCorrAccount());
        payload.put("currentAccount", paymentType.getCurrentAccount());
    }

    // -------------------------------------------------------------- внутреннее

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
                return MAPPER.readValue(resp.body(), AuthenticationDTO.class);
            } catch (ApiRequestException e) {
                throw e;
            } catch (IOException e) {
                lastException = e;
                log.warn("Попытка аутентификации {} не удалась: {}", attempt + 1, e.getMessage());
            }
        }
        throw new ApiException("ошибка аутентификации после повторных попыток", lastException);
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
            return MAPPER.readValue(resp.body(), RefreshTokenDTO.class);
        } catch (IOException e) {
            throw new ApiException("ошибка обновления токена", e);
        }
    }

    /**
     * Выполняет авторизованный запрос с автоматическим обновлением токена.
     *
     * <p>Запрос строится «лениво» через {@code reqFactory}: при повторе после 401 фабрика
     * вызывается заново и подставляет уже обновлённый Bearer-токен.</p>
     */
    private HttpExchange sendAuthorized(Supplier<HttpUriRequestBase> reqFactory, String errorMessage) {
        checkToken();
        try {
            return executeUnderReadLock(reqFactory.get(), errorMessage);
        } catch (ApiRequestException e) {
            if (e.getStatusCode() != 401) {
                throw e;
            }
            log.warn("Получен 401 — принудительно обновляю токен и повторяю запрос");
        }
        forceRefreshToken();
        return executeUnderReadLock(reqFactory.get(), errorMessage);
    }

    private HttpExchange executeUnderReadLock(HttpUriRequestBase req, String errorMessage) {
        refreshTokenLock.readLock().lock();
        try {
            return execute(req, errorMessage);
        } catch (IOException e) {
            throw new ApiException(errorMessage, e);
        } finally {
            refreshTokenLock.readLock().unlock();
        }
    }

    private void checkToken() {
        if (token == null) {
            throw new IllegalStateException("Клиент не инициализирован — вызовите init(username, password)");
        }
        if (isTokenExpiring()) {
            refreshTokenLock.writeLock().lock();
            try {
                if (isTokenExpiring()) { // повторная проверка под write-lock — исключает двойной refresh
                    doRefresh();
                }
            } finally {
                refreshTokenLock.writeLock().unlock();
            }
        }
    }

    private void forceRefreshToken() {
        refreshTokenLock.writeLock().lock();
        try {
            doRefresh();
        } finally {
            refreshTokenLock.writeLock().unlock();
        }
    }

    /** Обновляет токены. Вызывается строго под write-lock. */
    private void doRefresh() {
        RefreshTokenDTO dto = refreshToken();
        this.refreshToken = dto.getRefreshToken();
        this.token = dto.getToken();
        this.tokenExpireIn = dto.getTokenExpireIn();
        log.debug("Токен обновлён");
    }

    private boolean isTokenExpiring() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expire = OffsetDateTime.parse(tokenExpireIn);
        return now.plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS).isAfter(expire);
    }

    private String nowFormatted() {
        return OffsetDateTime.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .withOffsetSameInstant(ZoneOffset.of(clientConfig.getZoneOffset()))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String formatDateTime(OffsetDateTime dateTime) {
        return dateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireNonEmpty(List<IncomeItem> services) {
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("Список услуг не может быть пустым");
        }
    }

    private String generateDeviceId(String prefix) {
        SecureRandom random = new SecureRandom();
        final String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder deviceInfoBuilder = new StringBuilder(prefix);
        IntStream.range(0, Math.max(0, 21 - prefix.length())).forEach(v -> {
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

    /**
     * Закрывает HTTP-клиент и освобождает пул соединений.
     */
    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warn("Ошибка при закрытии HTTP-клиента: {}", e.getMessage());
        }
    }

    /** Простой DTO для пары status+body, чтобы передать через лямбду {@link CloseableHttpClient#execute}. */
    private record HttpExchange(int status, String body) {}
}
