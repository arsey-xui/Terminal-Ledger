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
 * 记账流水。分类/账户通过 {@code clientId} 引用，保证跨设备关联稳定。
 */
@Entity
@Table(name = "transaction_record", indexes = {
        @Index(name = "idx_txn_date", columnList = "date"),
        @Index(name = "idx_txn_client_id", columnList = "client_id"),
        @Index(name = "idx_txn_last_modified", columnList = "last_modified")
})
@Getter
@Setter
@NoArgsConstructor
public class Transaction extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** EXPENSE / INCOME */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "category_client_id", length = 36)
    private String categoryClientId;

    @Column(name = "account_client_id", length = 36)
    private String accountClientId;

    @Column(length = 512)
    private String note;

    /** 账单照片：云端保存的是相对路径（uploads/xxx.jpg）或 Base64 标记。 */
    @Column(name = "photo_path", length = 1024)
    private String photoPath;

    /** 原始凭证（数字小键盘/语音等），便于排查。 */
    @Column(length = 32)
    private String source;
}
