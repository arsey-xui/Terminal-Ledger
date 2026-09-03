package com.personal.accounting.repository;

import com.personal.accounting.entity.Template;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    Optional<Template> findByClientId(String clientId);

    List<Template> findByDeletedFalseOrderBySortOrderAsc();

    Page<Template> findByLastModifiedGreaterThan(Long lastModified, Pageable pageable);

    @Query("select t from Template t where t.lastModified > :ts or (t.lastModified = :ts and t.id > :id) order by t.lastModified asc, t.id asc")
    List<Template> keyset(@Param("ts") long ts, @Param("id") long id, Pageable pageable);
}
