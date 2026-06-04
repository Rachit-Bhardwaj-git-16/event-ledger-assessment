package com.eventledger.gateway.integration;

import com.eventledger.gateway.dto.EventRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

// WireMock static imports — fully qualified to avoid conflict with MockMvc's post()
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

// MockMvc static imports
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full integration test: Gateway → Account Service (mocked via WireMock).
 * Each test uses a unique accountId to avoid H2 state bleed between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventGatewayIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.url", () -> "http://localhost:" + wireMockServer.port());
    }

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    // ── Full Flow ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Integration — POST /events creates event, calls account service with X-Trace-Id")
    void testFullFlow_PostEvent() throws Exception {
        stubSuccess("acct-int-1", "evt-int-001");

        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-int-001", "acct-int-1", "CREDIT", "500.00", Instant.now())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventId").value("evt-int-001"))
            .andExpect(jsonPath("$.type").value("CREDIT"));

        wireMockServer.verify(postRequestedFor(urlEqualTo("/accounts/acct-int-1/transactions"))
            .withHeader("X-Trace-Id", matching(".+")));
    }

    @Test
    @Order(2)
    @DisplayName("Integration — Idempotency: same eventId second POST does NOT call account service again")
    void testFullFlow_Idempotency() throws Exception {
        stubSuccess("acct-int-1", "evt-int-001");

        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-int-001", "acct-int-1", "CREDIT", "500.00", Instant.now())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventId").value("evt-int-001"));

        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/accounts/acct-int-1/transactions")));
    }

    @Test
    @Order(3)
    @DisplayName("Integration — GET /events/{id} works even when account service is down")
    void testGetEvent_WorksWithoutAccountService() throws Exception {
        stubSuccess("acct-int-2", "evt-int-002");

        String createResp = mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-int-002", "acct-int-2", "DEBIT", "75.00", Instant.now())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(createResp).get("id").asLong();
        wireMockServer.resetAll();

        mockMvc.perform(get("/events/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value("evt-int-002"));
    }

    @Test
    @Order(4)
    @DisplayName("Integration — GET /events?account returns events in chronological order (out-of-order handling)")
    void testGetEventsByAccount_OutOfOrder() throws Exception {
        String acct = "acct-order";
        Instant earlier = Instant.parse("2026-01-01T08:00:00Z");
        Instant later   = Instant.parse("2026-01-01T10:00:00Z");

        stubSuccess(acct, "evt-later");
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-later", acct, "CREDIT", "50.00", later)))
            .andExpect(status().isCreated());

        stubSuccess(acct, "evt-earlier");
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-earlier", acct, "CREDIT", "100.00", earlier)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/events").param("account", acct))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventId").value("evt-earlier"))
            .andExpect(jsonPath("$[1].eventId").value("evt-later"));
    }

    // ── Resiliency ──────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Resiliency — POST /events returns 503 when account service returns 500")
    void testResiliency_AccountServiceDown_Returns503() throws Exception {
        stubFor(WireMock.post(urlEqualTo("/accounts/acct-fail/transactions"))
            .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"Internal Server Error\"}")));

        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-fail-001", "acct-fail", "CREDIT", "100.00", Instant.now())))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    @Order(6)
    @DisplayName("Trace propagation — client-supplied X-Trace-Id forwarded to account service")
    void testTracePropagation_ClientTraceIdForwarded() throws Exception {
        stubSuccess("acct-trace", "evt-trace-int");

        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", "client-trace-999")
                .content(json("evt-trace-int", "acct-trace", "CREDIT", "10.00", Instant.now())))
            .andExpect(status().isCreated());

        wireMockServer.verify(postRequestedFor(urlEqualTo("/accounts/acct-trace/transactions"))
            .withHeader("X-Trace-Id", equalTo("client-trace-999")));
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Validation — missing eventId returns 400")
    void testValidation_MissingEventId() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(json(null, "acct-v", "CREDIT", "100.00", Instant.now())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.eventId").exists());
    }

    @Test
    @DisplayName("Validation — zero amount returns 400")
    void testValidation_ZeroAmount() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-zero", "acct-v", "CREDIT", "0.00", Instant.now())))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Validation — negative amount returns 400")
    void testValidation_NegativeAmount() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(json("evt-neg", "acct-v", "CREDIT", "-50.00", Instant.now())))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Validation — invalid event type returns 400")
    void testValidation_InvalidEventType() throws Exception {
        String body = """
            {"eventId":"evt-type","accountId":"acct-v","type":"TRANSFER",
             "amount":100.00,"currency":"USD","eventTimestamp":"2026-05-01T10:00:00Z"}
            """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Validation — lowercase currency returns 400")
    void testValidation_LowercaseCurrency() throws Exception {
        String body = """
            {"eventId":"evt-cur","accountId":"acct-v","type":"CREDIT",
             "amount":100.00,"currency":"usd","eventTimestamp":"2026-05-01T10:00:00Z"}
            """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private void stubSuccess(String accountId, String eventId) {
        stubFor(WireMock.post(urlEqualTo("/accounts/" + accountId + "/transactions"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"id":1,"accountId":"%s","eventId":"%s","type":"CREDIT","amount":100.00,"currency":"USD"}
                    """.formatted(accountId, eventId))));
    }

    private String json(String eventId, String accountId, String type, String amount, Instant ts) throws Exception {
        EventRequest r = EventRequest.builder()
            .eventId(eventId).accountId(accountId).type(type)
            .amount(amount != null ? new BigDecimal(amount) : null)
            .currency("USD").eventTimestamp(ts).build();
        return objectMapper.writeValueAsString(r);
    }
}