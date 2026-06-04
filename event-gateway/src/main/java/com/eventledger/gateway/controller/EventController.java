package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
@Slf4j
@Tag(name = "Events", description = "Event management endpoints")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(
        summary = "Create a new event",
        description = "Creates a new event. Idempotent - same eventId returns original event."
    )
    @ApiResponse(responseCode = "201", description = "Event created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "503", description = "Account service unavailable")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        log.info("POST /events - Creating event: {}", request.getEventId());
        EventResponse response = eventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get event by ID")
    @ApiResponse(responseCode = "200", description = "Event found")
    @ApiResponse(responseCode = "404", description = "Event not found")
    public ResponseEntity<EventResponse> getEvent(
            @Parameter(description = "Event ID")
            @PathVariable("id") Long id) {
        log.info("GET /events/{} - Fetching event", id);
        EventResponse response = eventService.getEvent(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "Get events by account ID")
    @ApiResponse(responseCode = "200", description = "Events retrieved")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @Parameter(description = "Account ID")
            @RequestParam(name = "account", required = false) String account) {
        log.info("GET /events?account={} - Fetching events", account);
        List<EventResponse> response;
        if (account != null) {
            response = eventService.getEventsByAccount(account);
        } else {
            response = eventService.getAllEvents();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}