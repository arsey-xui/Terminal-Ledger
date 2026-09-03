package com.personal.accounting.dto.sync;

import com.personal.accounting.dto.AccountDto;
import com.personal.accounting.dto.BudgetDto;
import com.personal.accounting.dto.CategoryDto;
import com.personal.accounting.dto.TemplateDto;
import com.personal.accounting.dto.TransactionDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 云端 → 客户端的拉取响应。服务端 {@code lastModified > since} 的记录才会返回。
 */
@Data
public class SyncPullResponse {
    private long serverTime;
    private boolean hasMore;
    private String nextCursor;
    private List<TransactionDto> transactions = new ArrayList<>();
    private List<CategoryDto> categories = new ArrayList<>();
    private List<AccountDto> accounts = new ArrayList<>();
    private List<BudgetDto> budgets = new ArrayList<>();
    private List<TemplateDto> templates = new ArrayList<>();
}
