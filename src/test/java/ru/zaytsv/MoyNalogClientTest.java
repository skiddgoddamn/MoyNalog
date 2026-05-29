package ru.zaytsv;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.zaytsv.config.MoyNalogClientConfig;
import ru.zaytsv.dto.AuthenticationDTO;
import ru.zaytsv.exception.ApiRequestException;
import ru.zaytsv.model.CancelReason;
import ru.zaytsv.model.IncomeItem;
import ru.zaytsv.model.IncomesPage;
import ru.zaytsv.model.IncomesQuery;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты {@link MoyNalogClient} на mock HTTP-сервере.
 */
class MoyNalogClientTest {

    private MockWebServer server;
    private MoyNalogClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        MoyNalogClientConfig config = new MoyNalogClientConfig();
        config.setApiPath(server.url("/api/v1").toString());
        client = new MoyNalogClient(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    // --------------------------------------------------------------- helpers

    private static String futureIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
    }

    private static String pastIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toString();
    }

    private void enqueueAuth(String token, String expireIso) {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"refreshToken\":\"R1\",\"token\":\"" + token + "\",\"tokenExpireIn\":\"" + expireIso
                        + "\",\"profile\":{\"inn\":\"123456789012\",\"displayName\":\"Тест\"}}"));
    }

    private void initWithValidToken() {
        enqueueAuth("T1", futureIso());
        AuthenticationDTO.Profile profile = client.init("user", "pass");
        assertThat(profile.getInn()).isEqualTo("123456789012");
    }

    // ----------------------------------------------------------------- tests

    @Test
    void addIncomeSendsCorrectPayloadAndParsesReceipt() throws InterruptedException {
        initWithValidToken();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"approvedReceiptUuid\":\"uuid-1\"}"));

        var receipt = client.addIncome(List.of(new IncomeItem("Консультация", 2, new BigDecimal("1500.00"))));

        assertThat(receipt.uuid()).isEqualTo("uuid-1");
        assertThat(receipt.jsonUrl()).endsWith("/receipt/123456789012/uuid-1/json");

        server.takeRequest(); // /auth/lkfl
        RecordedRequest income = server.takeRequest();
        assertThat(income.getPath()).endsWith("/income");
        assertThat(income.getHeader("Authorization")).isEqualTo("Bearer T1");
        String body = income.getBody().readUtf8();
        assertThat(body).contains("\"paymentType\":\"CASH\"");
        assertThat(body).contains("\"incomeType\":\"FROM_INDIVIDUAL\"");
        // 2 × 1500.00 = 3000.00 — сумма позиции и итог
        assertThat(body).contains("\"amount\":3000.00");
        assertThat(body).contains("\"totalAmount\":3000.00");
    }

    @Test
    void nonOkResponseThrowsApiRequestExceptionWithStatusAndBody() {
        initWithValidToken();
        server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"message\":\"плохой запрос\"}"));

        assertThatExceptionOfType(ApiRequestException.class)
                .isThrownBy(() -> client.addIncome(List.of(new IncomeItem("X", 1, 100.0))))
                .satisfies(e -> {
                    assertThat(e.getStatusCode()).isEqualTo(422);
                    assertThat(e.getBody().toString()).contains("плохой запрос");
                });
    }

    @Test
    void expiredTokenIsRefreshedBeforeRequest() throws InterruptedException {
        enqueueAuth("T1", pastIso());              // токен «протух» сразу
        client.init("user", "pass");
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"token\":\"T2\",\"refreshToken\":\"R2\",\"tokenExpireIn\":\"" + futureIso() + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"inn\":\"123456789012\",\"displayName\":\"Тест\"}"));

        client.getUser();

        server.takeRequest(); // /auth/lkfl
        assertThat(server.takeRequest().getPath()).endsWith("/auth/token"); // refresh до запроса
        RecordedRequest user = server.takeRequest();
        assertThat(user.getPath()).endsWith("/user");
        assertThat(user.getHeader("Authorization")).isEqualTo("Bearer T2"); // уже новый токен
    }

    @Test
    void receivingA401ForcesRefreshAndRetriesOnce() throws InterruptedException {
        initWithValidToken();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"token expired\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"token\":\"T2\",\"refreshToken\":\"R2\",\"tokenExpireIn\":\"" + futureIso() + "\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"approvedReceiptUuid\":\"uuid-2\"}"));

        var receipt = client.addIncome(List.of(new IncomeItem("X", 1, 100.0)));
        assertThat(receipt.uuid()).isEqualTo("uuid-2");

        server.takeRequest(); // /auth/lkfl
        assertThat(server.takeRequest().getPath()).endsWith("/income");      // 401
        assertThat(server.takeRequest().getPath()).endsWith("/auth/token");  // принудительный refresh
        RecordedRequest retry = server.takeRequest();
        assertThat(retry.getPath()).endsWith("/income");
        assertThat(retry.getHeader("Authorization")).isEqualTo("Bearer T2"); // повтор с новым токеном
    }

    @Test
    void cancelReceiptSendsReasonComment() throws InterruptedException {
        initWithValidToken();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.cancelReceipt("uuid-1", CancelReason.REFUND);

        server.takeRequest(); // /auth/lkfl
        RecordedRequest cancel = server.takeRequest();
        assertThat(cancel.getPath()).endsWith("/cancel");
        String body = cancel.getBody().readUtf8();
        assertThat(body).contains("\"receiptUuid\":\"uuid-1\"");
        assertThat(body).contains("\"comment\":\"Возврат средств\"");
    }

    @Test
    void getIncomesBuildsQueryParameters() throws InterruptedException {
        initWithValidToken();
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"content\":[{\"approvedReceiptUuid\":\"u\",\"totalAmount\":50.00}],\"hasMore\":false}"));

        IncomesPage page = client.getIncomes(new IncomesQuery().limit(5).offset(10));

        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getTotalAmount()).isEqualByComparingTo("50.00");

        server.takeRequest(); // /auth/lkfl
        String path = server.takeRequest().getPath();
        assertThat(path).contains("/incomes?");
        assertThat(path).contains("offset=10");
        assertThat(path).contains("limit=5");
    }

    @Test
    void cancelInvoiceHitsCancelEndpointWithoutBody() throws InterruptedException {
        initWithValidToken();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"invoiceId\":42,\"status\":\"CANCELLED\"}"));

        var invoice = client.cancelInvoice(42L);

        assertThat(invoice.getStatus()).isEqualTo("CANCELLED");
        server.takeRequest(); // /auth/lkfl
        RecordedRequest cancel = server.takeRequest();
        assertThat(cancel.getPath()).endsWith("/invoice/42/cancel");
        assertThat(cancel.getBody().readUtf8()).isEmpty();
    }

    @Test
    void callingBeforeInitThrowsIllegalState() {
        assertThatThrownBy(() -> client.getUser())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyServicesAreRejected() {
        assertThatThrownBy(() -> client.addIncome(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
