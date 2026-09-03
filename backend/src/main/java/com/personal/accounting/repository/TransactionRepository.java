package com.personal.accounting.repository;

import com.personal.accounting.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByClientId(String clientId);

    List<Transaction> findByDeletedFalseAndDateBetween(LocalDate start, LocalDate end);

    Page<Transaction> findByLastModifiedGreaterThan(Long lastModified, Pageable pageable);

    // ---- 报表查询 ----
    List<Transaction> findByDeletedFalseAndTypeAndDateBetween(String type, LocalDate start, LocalDate end);

    List<Transaction> findByDeletedFalseAndDateAfter(LocalDate since);

    @Query("select t from Transaction t where t.lastModified > :ts or (t.lastModified = :ts and t.id > :id) order by t.lastModified asc, t.id asc")
    List<Transaction> keyset(@Param("ts") long ts, @Param("id") long id, Pageable pageable);
}
