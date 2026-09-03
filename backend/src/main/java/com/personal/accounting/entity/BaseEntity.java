package com.personal.accounting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 所有可同步实体的公共字段。
 *
 * <p>跨设备稳定引用一律使用 {@link #clientId}（客户端生成 UUID），而不是数据库自增主键，
 * 这样一台设备上新建的分类/账户能被另一台设备正确关联，且不受主键冲突影响。</p>
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 全局唯一的逻辑主键，由客户端在创建时生成（UUID）。 */
    @Column(name = "client_id", nullable = false, unique = true, length = 36)
    private String clientId;

    /** LWW 合并依据：最后修改时间（Unix epoch 毫秒）。 */
    @Column(name = "last_modified", nullable = false)
    private Long lastModified;

    /** 软删除墓碑，保证删除操作也能被同步。 */
    @Column(nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
