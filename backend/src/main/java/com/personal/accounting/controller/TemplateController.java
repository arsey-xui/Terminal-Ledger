package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.TemplateDto;
import com.personal.accounting.entity.Template;
import com.personal.accounting.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateRepository repository;

    @GetMapping
    public ApiResponse<List<TemplateDto>> list() {
        return ApiResponse.ok(repository.findByDeletedFalseOrderBySortOrderAsc().stream()
                .map(TemplateDto::from).toList());
    }

    @PostMapping
    public ApiResponse<TemplateDto> create(@RequestBody TemplateDto dto) {
        Template t = new Template();
        t.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        apply(dto, t);
        repository.save(t);
        return ApiResponse.ok(TemplateDto.from(t), "已创建");
    }

    @PutMapping("/{clientId}")
    public ApiResponse<TemplateDto> update(@PathVariable String clientId, @RequestBody TemplateDto dto) {
        return repository.findByClientId(clientId)
                .map(t -> {
                    apply(dto, t);
                    t.setLastModified(System.currentTimeMillis());
                    repository.save(t);
                    return ApiResponse.ok(TemplateDto.from(t), "已更新");
                })
                .orElseGet(() -> ApiResponse.fail("模板不存在"));
    }

    @DeleteMapping("/{clientId}")
    public ApiResponse<Void> delete(@PathVariable String clientId) {
        repository.findByClientId(clientId).ifPresent(t -> {
            t.setDeleted(true);
            t.setLastModified(System.currentTimeMillis());
            repository.save(t);
        });
        return ApiResponse.ok(null, "已删除（软删除）");
    }

    private void apply(TemplateDto dto, Template t) {
        t.setName(dto.getName());
        t.setAmount(dto.getAmount());
        t.setType(dto.getType());
        t.setCategoryClientId(dto.getCategoryClientId());
        t.setAccountClientId(dto.getAccountClientId());
        t.setNote(dto.getNote());
        t.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        t.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        t.setDeleted(false);
        t.setCreatedAt(t.getCreatedAt() == null ? LocalDateTime.now() : t.getCreatedAt());
        t.setUpdatedAt(LocalDateTime.now());
    }
}
