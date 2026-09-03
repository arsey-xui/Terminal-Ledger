package com.personal.accounting.repository;

import com.personal.accounting.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByClientId(String clientId);

    List<Account> findByDeletedFalseOrderBySortOrderAsc();

    Page<Account> findByLastModifiedGreaterThan(Long lastModified, Pageable pageable);

    @Query("select a from Account a where a.lastModified > :ts or (a.lastModified = :ts and a.id > :id) order by a.lastModified asc, a.id asc")
    List<Account> keyset(@Param("ts") long ts, @Param("id") long id, Pageable pageable);
}
