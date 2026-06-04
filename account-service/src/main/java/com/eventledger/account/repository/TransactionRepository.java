package com.eventledger.account.repository;

import com.eventledger.account.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByEventId(String eventId);

    @Query("SELECT t FROM Transaction t WHERE t.accountId = ?1 ORDER BY t.eventTimestamp ASC, t.createdAt ASC")
    List<Transaction> findByAccountIdOrderedByTimestamp(String accountId);
}