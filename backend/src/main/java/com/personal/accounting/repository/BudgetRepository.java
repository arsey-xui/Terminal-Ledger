package com.personal.accounting.repository;

import com.personal.accounting.entity.Budget;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    Optional<Budget> findByClientId(String clientId);

    Optional<Budget> findByCategoryClientIdAndYearAndMonth(String categoryClientId, int year, int month);

    List<Budget> findByYearAndMonthAndDeletedFalse(int year, int month);

    Page<Budget> findByLastModifiedGreaterThan(Long lastModified, Pageable pageable);

    @Query("select b from Budget b where b.lastModified > :ts or (b.lastModified = :ts and b.id > :id) order by b.lastModified asc, b.id asc")
    List<Budget> keyset(@Param("ts") long ts, @Param("id") long id, Pageable pageable);
}
