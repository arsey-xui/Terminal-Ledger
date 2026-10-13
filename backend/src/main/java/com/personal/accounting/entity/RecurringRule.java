package com.personal.accounting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 周期记账规则（自动续费 / 固定收支）：每 N 天、周或月到期一次。
 *
 * <p>到期不会自动改账，而是在客户端生成「待确认项」，由用户确认后才写入流水，
 * 所以这里只保存规则本身，不保存生成结果。</p>
 */
@Entity
@Table(name = "recurring_rule", indexes = {
        @Index(name = "idx_recurring_last_modified", columnList = "last_modified")
})
@Getter
@Setter
@NoArgsConstructor
public class RecurringRule extends BaseEntity {

    /** 显示名，如「房租」「视频会员」。 */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    /** EXPENSE / INCOME */
    @Column(name = "txn_type", length = 16, nullable = false)
    private String type;

    @Column(name = "category_client_id", length = 36)
    private String categoryClientId;

    @Column(name = "account_client_id", length = 36)
    private String accountClientId;

    @Column(length = 512)
    private String note;

    /** 间隔单位：DAY / WEEK / MONTH。 */
    @Column(name = "interval_unit", length = 8, nullable = false)
    private String intervalUnit;

    /** 间隔数量：每 N 个单位一次（如 N=30、单位 DAY 即「每 30 天」）。 */
    @Column(name = "interval_count", nullable = false)
    private Integer intervalCount = 1;

    /** 首次（基准）应记日期，后续到期日由此按间隔推算。 */
    @Column(name = "anchor_date", nullable = false)
    private LocalDate anchorDate;

    /** 已生成到哪一天（含），用于避免重复生成。 */
    @Column(name = "last_generated_date")
    private LocalDate lastGeneratedDate;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
