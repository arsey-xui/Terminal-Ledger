package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.CategoryDto;
import com.personal.accounting.entity.Category;
import com.personal.accounting.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository repository;

    @GetMapping
    public ApiResponse<List<CategoryDto>> list() {
        return ApiResponse.ok(repository.findByDeletedFalseOrderBySortOrderAsc().stream()
                .map(CategoryDto::from).toList());
    }

    @PostMapping
    public ApiResponse<CategoryDto> create(@RequestBody CategoryDto dto) {
        Category c = new Category();
        c.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        apply(dto, c);
        repository.save(c);
        return ApiResponse.ok(CategoryDto.from(c), "已创建");
    }

    @PutMapping("/{clientId}")
    public ApiResponse<CategoryDto> update(@PathVariable String clientId, @RequestBody CategoryDto dto) {
        return repository.findByClientId(clientId)
                .map(c -> {
                    apply(dto, c);
                    c.setLastModified(System.currentTimeMillis());
                    repository.save(c);
                    return ApiResponse.ok(CategoryDto.from(c), "已更新");
                })
                .orElseGet(() -> ApiResponse.fail("分类不存在"));
    }

    @DeleteMapping("/{clientId}")
    public ApiResponse<Void> delete(@PathVariable String clientId) {
        repository.findByClientId(clientId).ifPresent(c -> {
            c.setDeleted(true);
            c.setLastModified(System.currentTimeMillis());
            repository.save(c);
        });
        return ApiResponse.ok(null, "已删除（软删除）");
    }

    private void apply(CategoryDto dto, Category c) {
        c.setName(dto.getName());
        c.setType(dto.getType() == null ? "EXPENSE" : dto.getType());
        c.setIcon(dto.getIcon());
        c.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        c.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        c.setDeleted(false);
        c.setCreatedAt(c.getCreatedAt() == null ? LocalDateTime.now() : c.getCreatedAt());
        c.setUpdatedAt(LocalDateTime.now());
    }
}
