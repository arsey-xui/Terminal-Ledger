package com.personal.accounting.repository;

import com.personal.accounting.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByClientId(String clientId);

    List<Category> findByDeletedFalseOrderBySortOrderAsc();

    Page<Category> findByLastModifiedGreaterThan(Long lastModified, Pageable pageable);

    @Query("select c from Category c where c.lastModified > :ts or (c.lastModified = :ts and c.id > :id) order by c.lastModified asc, c.id asc")
    List<Category> keyset(@Param("ts") long ts, @Param("id") long id, Pageable pageable);
}
