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
 * 客户端 → 云端的推送请求。各类型为空则不处理；delete 通过 {@code deleted=true} 墓碑表达。
 */
@Data
public class SyncPushRequest {
    private String deviceId;
    private List<TransactionDto> transactions = new ArrayList<>();
    private List<CategoryDto> categories = new ArrayList<>();
    private List<AccountDto> accounts = new ArrayList<>();
    private List<BudgetDto> budgets = new ArrayList<>();
    private List<TemplateDto> templates = new ArrayList<>();
}
