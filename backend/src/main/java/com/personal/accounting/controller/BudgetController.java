package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.BudgetDto;
import com.personal.accounting.entity.Budget;
import com.personal.accounting.repository.BudgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetRepository repository;

    /** 按 (categoryClientId, year, month) 唯一键做 upsert。 */
    @Transactional
    @PostMapping
    public ApiResponse<BudgetDto> upsert(@RequestBody BudgetDto dto) {
        Budget b = repository.findByCategoryClientIdAndYearAndMonth(
                        dto.getCategoryClientId(), dto.getYear(), dto.getMonth())
                .orElseGet(() -> {
                    Budget nb = new Budget();
                    nb.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
                    nb.setCategoryClientId(dto.getCategoryClientId());
                    nb.setYear(dto.getYear());
                    nb.setMonth(dto.getMonth());
                    return nb;
                });
        b.setAmountLimit(dto.getAmountLimit());
        b.setLastModified(System.currentTimeMillis());
        b.setDeleted(false);
        b.setCreatedAt(b.getCreatedAt() == null ? LocalDateTime.now() : b.getCreatedAt());
        b.setUpdatedAt(LocalDateTime.now());
        repository.save(b);
        return ApiResponse.ok(BudgetDto.from(b), "已保存");
    }

    @Transactional
    @DeleteMapping("/{clientId}")
    public ApiResponse<Void> delete(@PathVariable String clientId) {
        repository.findByClientId(clientId).ifPresent(b -> {
            b.setDeleted(true);
            b.setLastModified(System.currentTimeMillis());
            repository.save(b);
        });
        return ApiResponse.ok(null, "已删除（软删除）");
    }
}
