package com.personal.accounting.controller;

import com.personal.accounting.dto.AccountDto;
import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.entity.Account;
import com.personal.accounting.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository repository;

    @GetMapping
    public ApiResponse<List<AccountDto>> list() {
        return ApiResponse.ok(repository.findByDeletedFalseOrderBySortOrderAsc().stream()
                .map(AccountDto::from).toList());
    }

    @Transactional
    @PostMapping
    public ApiResponse<AccountDto> create(@RequestBody AccountDto dto) {
        Account a = new Account();
        a.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        apply(dto, a);
        repository.save(a);
        return ApiResponse.ok(AccountDto.from(a), "已创建");
    }

    @Transactional
    @PutMapping("/{clientId}")
    public ApiResponse<AccountDto> update(@PathVariable String clientId, @RequestBody AccountDto dto) {
        return repository.findByClientId(clientId)
                .map(a -> {
                    apply(dto, a);
                    a.setLastModified(System.currentTimeMillis());
                    repository.save(a);
                    return ApiResponse.ok(AccountDto.from(a), "已更新");
                })
                .orElseGet(() -> ApiResponse.fail("账户不存在"));
    }

    @Transactional
    @DeleteMapping("/{clientId}")
    public ApiResponse<Void> delete(@PathVariable String clientId) {
        repository.findByClientId(clientId).ifPresent(a -> {
            a.setDeleted(true);
            a.setLastModified(System.currentTimeMillis());
            repository.save(a);
        });
        return ApiResponse.ok(null, "已删除（软删除）");
    }

    private void apply(AccountDto dto, Account a) {
        a.setName(dto.getName());
        a.setType(dto.getType());
        a.setIcon(dto.getIcon());
        a.setInitialBalance(dto.getInitialBalance() == null ? java.math.BigDecimal.ZERO : dto.getInitialBalance());
        a.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        a.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        a.setDeleted(false);
        a.setCreatedAt(a.getCreatedAt() == null ? LocalDateTime.now() : a.getCreatedAt());
        a.setUpdatedAt(LocalDateTime.now());
    }
}
