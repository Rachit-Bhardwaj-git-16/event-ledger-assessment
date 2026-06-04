package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final Counter eventsCreatedCounter;
    private final Counter eventsFailedCounter;

    public EventService(EventRepository eventRepository,
                       AccountServiceClient accountServiceClient,
                       ObjectMapper objectMapper,
                       MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.eventsCreatedCounter = Counter.builder("events_created_total")
            .description("Total number of events created")
            .register(meterRegistry);
        this.eventsFailedCounter = Counter.builder("events_failed_total")
            .description("Total number of event creation failures")
            .register(meterRegistry);
    }

    public EventResponse createEvent(EventRequest request) {
        String traceId = MDC.get("traceId");

        log.info("Creating event with ID: {} for account: {}", request.getEventId(), request.getAccountId());

        // Validate event type early — before any DB or network call
        try {
            Event.EventType.valueOf(request.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid event type: " + request.getType() + ". Must be CREDIT or DEBIT");
        }

        // Idempotency check
        Optional<Event> existingEvent = eventRepository.findByEventId(request.getEventId());
        if (existingEvent.isPresent()) {
            log.warn("Duplicate event ID: {}. Returning existing event.", request.getEventId());
            return mapToResponse(existingEvent.get());
        }

        // IMPORTANT: call account service FIRST.
        // If it is down (503/circuit open), we throw BEFORE saving to gateway DB.
        // This prevents ghost events that have no corresponding balance update.
        accountServiceClient.recordTransaction(request, traceId);

        try {
            Event event = Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(Event.EventType.valueOf(request.getType().toUpperCase()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .metadataJson(request.getMetadata() != null ?
                    objectMapper.writeValueAsString(request.getMetadata()) : null)
                .traceId(traceId)
                .build();

            Event savedEvent = eventRepository.save(event);
            log.info("Event saved with ID: {}", savedEvent.getEventId());
            eventsCreatedCounter.increment();
            return mapToResponse(savedEvent);

        } catch (Exception e) {
            log.error("Failed to save event: {}", request.getEventId(), e);
            eventsFailedCounter.increment();
            throw new RuntimeException("Failed to save event after account service call", e);
        }
    }

    public EventResponse getEvent(Long eventId) {
        return eventRepository.findById(eventId)
            .map(this::mapToResponse)
            .orElseThrow(() -> {
                log.warn("Event not found with ID: {}", eventId);
                return new RuntimeException("Event not found with ID: " + eventId);
            });
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        log.info("Fetching events for account: {}", accountId);
        return eventRepository.findByAccountIdOrderedByTimestamp(accountId)
            .stream()
            .map(this::mapToResponse)
            .toList();
    }

    public List<EventResponse> getAllEvents() {
        return eventRepository.findAllOrderedByTimestamp()
            .stream()
            .map(this::mapToResponse)
            .toList();
    }

    private EventResponse mapToResponse(Event event) {
        try {
            return EventResponse.builder()
                .id(event.getId())
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType().name())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .metadata(event.getMetadataJson() != null ?
                    objectMapper.readValue(event.getMetadataJson(), java.util.Map.class) : null)
                .createdAt(event.getCreatedAt())
                .traceId(event.getTraceId())
                .build();
        } catch (Exception e) {
            log.error("Failed to map event to response", e);
            throw new RuntimeException("Failed to map event", e);
        }
    }
}
