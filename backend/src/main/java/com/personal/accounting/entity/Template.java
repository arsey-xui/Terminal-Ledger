package com.personal.accounting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 记账模板：保存常用组合（如「每月房贷」），一键填充表单。
 */
@Entity
@Table(name = "template", indexes = {
        @Index(name = "idx_template_last_modified", columnList = "last_modified")
})
@Getter
@Setter
@NoArgsConstructor
public class Template extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String name;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 16)
    private String type;

    @Column(name = "category_client_id", length = 36)
    private String categoryClientId;

    @Column(name = "account_client_id", length = 36)
    private String accountClientId;

    @Column(length = 512)
    private String note;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
