package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private AccountServiceClient accountServiceClient;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, accountServiceClient,
            new ObjectMapper(), new SimpleMeterRegistry());
        MDC.put("traceId", "test-trace-123");
    }

    // ── Core Functionality ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Create event — success, account service called BEFORE save")
    void testCreateEvent_AccountServiceCalledBeforeSave() {
        EventRequest request = buildRequest("evt-001", "CREDIT", "100.00");
        Event saved = buildEvent(1L, "evt-001", Event.EventType.CREDIT, "100.00");

        when(eventRepository.findByEventId("evt-001")).thenReturn(Optional.empty());
        doNothing().when(accountServiceClient).recordTransaction(any(), any());
        when(eventRepository.save(any())).thenReturn(saved);

        EventResponse response = eventService.createEvent(request);

        assertNotNull(response);
        assertEquals("evt-001", response.getEventId());

        // Verify ORDER: account service first, then save
        InOrder inOrder = inOrder(accountServiceClient, eventRepository);
        inOrder.verify(accountServiceClient).recordTransaction(eq(request), eq("test-trace-123"));
        inOrder.verify(eventRepository).save(any(Event.class));
    }

    @Test
    @DisplayName("Idempotency — duplicate eventId returns original, no save or account call")
    void testCreateEvent_Idempotency() {
        EventRequest request = buildRequest("evt-001", "CREDIT", "100.00");
        Event existing = buildEvent(1L, "evt-001", Event.EventType.CREDIT, "100.00");

        when(eventRepository.findByEventId("evt-001")).thenReturn(Optional.of(existing));

        EventResponse response = eventService.createEvent(request);

        assertEquals("evt-001", response.getEventId());
        verify(eventRepository, never()).save(any());
        verify(accountServiceClient, never()).recordTransaction(any(), any());
    }

    @Test
    @DisplayName("Out-of-order — events returned sorted by eventTimestamp ASC")
    void testGetEventsByAccount_OrderedByTimestamp() {
        Instant earlier = Instant.parse("2026-01-01T10:00:00Z");
        Instant later   = Instant.parse("2026-01-01T12:00:00Z");

        Event e1 = buildEventWithTimestamp(1L, "evt-001", earlier);
        Event e2 = buildEventWithTimestamp(2L, "evt-002", later);

        when(eventRepository.findByAccountIdOrderedByTimestamp("acct-123"))
            .thenReturn(List.of(e1, e2));

        List<EventResponse> responses = eventService.getEventsByAccount("acct-123");

        assertEquals(2, responses.size());
        assertTrue(responses.get(0).getEventTimestamp().isBefore(responses.get(1).getEventTimestamp()));
    }

    @Test
    @DisplayName("Validation — invalid type throws IllegalArgumentException")
    void testCreateEvent_InvalidType() {
        EventRequest request = buildRequest("evt-bad", "TRANSFER", "50.00");
        when(eventRepository.findByEventId("evt-bad")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> eventService.createEvent(request));
        verify(eventRepository, never()).save(any());
        verify(accountServiceClient, never()).recordTransaction(any(), any());
    }

    @Test
    @DisplayName("Get event by id — not found throws exception")
    void testGetEvent_NotFound() {
        when(eventRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> eventService.getEvent(999L));
    }

    // ── Resiliency ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Resiliency — 503 from account service propagates up; event NOT saved")
    void testCreateEvent_AccountServiceDown_Returns503_EventNotSaved() {
        EventRequest request = buildRequest("evt-down", "CREDIT", "200.00");
        when(eventRepository.findByEventId("evt-down")).thenReturn(Optional.empty());

        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Account Service is currently unavailable. Please try again later."))
            .when(accountServiceClient).recordTransaction(any(), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> eventService.createEvent(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        // CRITICAL: event must NOT be saved when account service is down
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Resiliency — GET /events works without account service (no account call)")
    void testGetEvents_WorksWithoutAccountService() {
        Event e = buildEvent(1L, "evt-001", Event.EventType.CREDIT, "100.00");
        when(eventRepository.findByAccountIdOrderedByTimestamp("acct-123"))
            .thenReturn(List.of(e));

        List<EventResponse> responses = eventService.getEventsByAccount("acct-123");

        assertEquals(1, responses.size());
        verifyNoInteractions(accountServiceClient);
    }

    // ── Trace Propagation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Trace propagation — traceId from MDC passed to account service")
    void testTraceId_PropagatedToAccountService() {
        MDC.put("traceId", "trace-abc-xyz");
        EventRequest request = buildRequest("evt-trace", "CREDIT", "10.00");
        Event saved = buildEvent(1L, "evt-trace", Event.EventType.CREDIT, "10.00");

        when(eventRepository.findByEventId("evt-trace")).thenReturn(Optional.empty());
        doNothing().when(accountServiceClient).recordTransaction(any(), any());
        when(eventRepository.save(any())).thenReturn(saved);

        eventService.createEvent(request);

        verify(accountServiceClient).recordTransaction(eq(request), eq("trace-abc-xyz"));
    }

    @Test
    @DisplayName("Trace propagation — traceId stored on saved event entity")
    void testTraceId_StoredOnEventEntity() {
        MDC.put("traceId", "trace-stored-456");
        EventRequest request = buildRequest("evt-t2", "DEBIT", "25.00");
        Event saved = buildEvent(2L, "evt-t2", Event.EventType.DEBIT, "25.00");

        when(eventRepository.findByEventId("evt-t2")).thenReturn(Optional.empty());
        doNothing().when(accountServiceClient).recordTransaction(any(), any());
        when(eventRepository.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            assertEquals("trace-stored-456", e.getTraceId());
            return saved;
        });

        eventService.createEvent(request);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private EventRequest buildRequest(String eventId, String type, String amount) {
        return EventRequest.builder()
            .eventId(eventId).accountId("acct-123").type(type)
            .amount(new BigDecimal(amount)).currency("USD")
            .eventTimestamp(Instant.now()).build();
    }

    private Event buildEvent(Long id, String eventId, Event.EventType type, String amount) {
        return Event.builder().id(id).eventId(eventId).accountId("acct-123")
            .type(type).amount(new BigDecimal(amount)).currency("USD")
            .eventTimestamp(Instant.now()).build();
    }

    private Event buildEventWithTimestamp(Long id, String eventId, Instant timestamp) {
        return Event.builder().id(id).eventId(eventId).accountId("acct-123")
            .type(Event.EventType.CREDIT).amount(new BigDecimal("100.00"))
            .currency("USD").eventTimestamp(timestamp).build();
    }
}
