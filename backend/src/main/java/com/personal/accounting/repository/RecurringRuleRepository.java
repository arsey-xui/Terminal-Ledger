package com.personal.accounting.repository;

import com.personal.accounting.entity.RecurringRule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {

    Optional<RecurringRule> findByClientId(String clientId);

    List<RecurringRule> findByDeletedFalseOrderBySortOrderAscIdAsc();

    @Query("select r from RecurringRule r where r.lastModified > :ts or (r.lastModified = :ts and r.id > :id) "
            + "order by r.lastModified asc, r.id asc")
    List<RecurringRule> keyset(@Param("ts") long ts, @Param("id") long id, Pageable pageable);
}
