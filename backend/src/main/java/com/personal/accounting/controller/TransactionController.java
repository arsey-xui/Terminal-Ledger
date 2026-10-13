package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.TransactionDto;
import com.personal.accounting.entity.Transaction;
import com.personal.accounting.repository.TransactionRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository repository;

    /** 组合筛选：日期范围 + 分类 + 账户 + 金额区间 + 备注关键词。 */
    @GetMapping
    public ApiResponse<Page<TransactionDto>> search(
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String categoryClientId,
            @RequestParam(required = false) String accountClientId,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("deleted"), false));
            if (dateFrom != null) ps.add(cb.greaterThanOrEqualTo(root.get("date"), dateFrom));
            if (dateTo != null) ps.add(cb.lessThanOrEqualTo(root.get("date"), dateTo));
            if (categoryClientId != null) ps.add(cb.equal(root.get("categoryClientId"), categoryClientId));
            if (accountClientId != null) ps.add(cb.equal(root.get("accountClientId"), accountClientId));
            if (amountMin != null) ps.add(cb.greaterThanOrEqualTo(root.get("amount"), amountMin));
            if (amountMax != null) ps.add(cb.lessThanOrEqualTo(root.get("amount"), amountMax));
            if (keyword != null && !keyword.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("note")), "%" + keyword.toLowerCase() + "%"));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Transaction> result = repository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id"))));
        return ApiResponse.ok(result.map(TransactionDto::from));
    }

    @GetMapping("/{clientId}")
    public ApiResponse<TransactionDto> get(@PathVariable String clientId) {
        return repository.findByClientId(clientId)
                .map(t -> ApiResponse.ok(TransactionDto.from(t)))
                .orElseGet(() -> ApiResponse.fail("流水不存在"));
    }

    @PostMapping
    public ApiResponse<TransactionDto> create(@RequestBody TransactionDto dto) {
        Transaction t = new Transaction();
        t.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        apply(dto, t);
        repository.save(t);
        return ApiResponse.ok(TransactionDto.from(t), "已记账");
    }

    @Transactional
    @PutMapping("/{clientId}")
    public ApiResponse<TransactionDto> update(@PathVariable String clientId, @RequestBody TransactionDto dto) {
        return repository.findByClientId(clientId)
                .map(t -> {
                    apply(dto, t);
                    t.setClientId(clientId);
                    t.setLastModified(System.currentTimeMillis());
                    repository.save(t);
                    return ApiResponse.ok(TransactionDto.from(t), "已更新");
                })
                .orElseGet(() -> ApiResponse.fail("流水不存在"));
    }

    @Transactional
    @DeleteMapping("/{clientId}")
    public ApiResponse<Void> delete(@PathVariable String clientId) {
        repository.findByClientId(clientId).ifPresent(t -> {
            t.setDeleted(true);
            t.setLastModified(System.currentTimeMillis());
            repository.save(t);
        });
        return ApiResponse.ok(null, "已删除（软删除）");
    }

    private void apply(TransactionDto dto, Transaction t) {
        t.setDate(dto.getDate() == null ? LocalDate.now() : dto.getDate());
        t.setAmount(dto.getAmount());
        t.setType(dto.getType() == null ? "EXPENSE" : dto.getType());
        t.setCategoryClientId(dto.getCategoryClientId());
        t.setAccountClientId(dto.getAccountClientId());
        t.setNote(dto.getNote());
        t.setPhotoPath(dto.getPhotoPath());
        t.setSource(dto.getSource());
        t.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        t.setDeleted(false);
        t.setCreatedAt(t.getCreatedAt() == null ? LocalDateTime.now() : t.getCreatedAt());
        t.setUpdatedAt(LocalDateTime.now());
    }
}
