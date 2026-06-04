package com.eventledger.gateway.repository;

import com.eventledger.gateway.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    @Query("SELECT e FROM Event e WHERE e.accountId = ?1 ORDER BY e.eventTimestamp ASC, e.createdAt ASC")
    List<Event> findByAccountIdOrderedByTimestamp(String accountId);

    @Query("SELECT e FROM Event e ORDER BY e.eventTimestamp ASC, e.createdAt ASC")
    List<Event> findAllOrderedByTimestamp();
}