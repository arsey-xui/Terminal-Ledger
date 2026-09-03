package com.personal.accounting.dto;

import com.personal.accounting.entity.Category;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CategoryDto {
    private String clientId;
    private String name;
    private String type;
    private String icon;
    private Integer sortOrder;
    private Long lastModified;
    private Boolean deleted;
    private LocalDateTime createdAt;

    public static CategoryDto from(Category c) {
        CategoryDto d = new CategoryDto();
        d.clientId = c.getClientId();
        d.name = c.getName();
        d.type = c.getType();
        d.icon = c.getIcon();
        d.sortOrder = c.getSortOrder();
        d.lastModified = c.getLastModified();
        d.deleted = c.getDeleted();
        d.createdAt = c.getCreatedAt();
        return d;
    }
}
