package com.personal.accounting.service.impl;

import com.personal.accounting.config.SyncProperties;
import com.personal.accounting.dto.AccountDto;
import com.personal.accounting.dto.BudgetDto;
import com.personal.accounting.dto.CategoryDto;
import com.personal.accounting.dto.RecurringRuleDto;
import com.personal.accounting.dto.TemplateDto;
import com.personal.accounting.dto.TransactionDto;
import com.personal.accounting.dto.sync.SyncPullRequest;
import com.personal.accounting.dto.sync.SyncPullResponse;
import com.personal.accounting.dto.sync.SyncPushRequest;
import com.personal.accounting.dto.sync.SyncResult;
import com.personal.accounting.entity.Account;
import com.personal.accounting.entity.BaseEntity;
import com.personal.accounting.entity.Budget;
import com.personal.accounting.entity.Category;
import com.personal.accounting.entity.RecurringRule;
import com.personal.accounting.entity.Template;
import com.personal.accounting.entity.Transaction;
import com.personal.accounting.repository.AccountRepository;
import com.personal.accounting.repository.BudgetRepository;
import com.personal.accounting.repository.CategoryRepository;
import com.personal.accounting.repository.RecurringRuleRepository;
import com.personal.accounting.repository.TemplateRepository;
import com.personal.accounting.repository.TransactionRepository;
import com.personal.accounting.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * LWW 合并同步实现。
 *
 * <p>算法核心（以 account 为例，其它实体同理）：</p>
 * <pre>
 *   existing = repo.findByClientId(incoming.clientId)
 *   if (existing == null)        -&gt; insert
 *   else if incoming.lastModified &gt; existing.lastModified -&gt; overwrite  (LWW wins)
 *   else                         -&gt; skip (local copy is newer / equal)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final TemplateRepository templateRepository;
    private final RecurringRuleRepository recurringRuleRepository;
    private final SyncProperties props;

    @Override
    @Transactional
    public SyncResult push(SyncPushRequest request) {
        SyncResult result = new SyncResult();
        result.setPushedAt(now());

        for (AccountDto dto : request.getAccounts()) {
            if (mergeAccount(dto)) result.setUpserted(result.getUpserted() + 1);
            else result.setSkipped(result.getSkipped() + 1);
        }
        for (CategoryDto dto : request.getCategories()) {
            if (mergeCategory(dto)) result.setUpserted(result.getUpserted() + 1);
            else result.setSkipped(result.getSkipped() + 1);
        }
        for (TransactionDto dto : request.getTransactions()) {
            if (mergeTransaction(dto)) result.setUpserted(result.getUpserted() + 1);
            else result.setSkipped(result.getSkipped() + 1);
        }
        for (BudgetDto dto : request.getBudgets()) {
            if (mergeBudget(dto)) result.setUpserted(result.getUpserted() + 1);
            else result.setSkipped(result.getSkipped() + 1);
        }
        for (TemplateDto dto : request.getTemplates()) {
            if (mergeTemplate(dto)) result.setUpserted(result.getUpserted() + 1);
            else result.setSkipped(result.getSkipped() + 1);
        }
        for (RecurringRuleDto dto : request.getRecurringRules()) {
            if (mergeRecurringRule(dto)) result.setUpserted(result.getUpserted() + 1);
            else result.setSkipped(result.getSkipped() + 1);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public SyncPullResponse pull(SyncPullRequest request) {
        long since = request.getSince() == null ? 0L : request.getSince();
        boolean fullSync = since == 0L;
        if (fullSync && props.getPullWindowMonths() != null && request.getCursor() == null) {
            // 首次同步：只拉最近 N 个月，避免一次性拉全量；更早数据走 pullWindow。
            since = now() - java.util.concurrent.TimeUnit.DAYS.toMillis(props.getPullWindowMonths() * 30L);
        }
        long[] keyset = decodeCursor(request.getCursor(), since);
        return listChanges(keyset[0], keyset[1], true);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncPullResponse pullWindow(SyncPullRequest request) {
        long from = request.getSince() == null ? 0L : request.getSince();
        long until = request.getUntil() == null ? now() : request.getUntil();
        long[] keyset = decodeCursor(request.getCursor(), from);
        return listWindow(keyset[0], until, request.getCursor() != null ? keyset[1] : 0L);
    }

    // ------------------------------------------------------------------
    //  LWW merge（每种实体一个）
    // ------------------------------------------------------------------

    private boolean mergeAccount(AccountDto dto) {
        Optional<Account> opt = accountRepository.findByClientId(dto.getClientId());
        if (opt.isEmpty()) {
            Account a = new Account();
            copy(dto, a);
            accountRepository.save(a);
            return true;
        }
        Account existing = opt.get();
        if (dto.getLastModified() != null && dto.getLastModified() > existing.getLastModified()) {
            copy(dto, existing);
            accountRepository.save(existing);
            return true;
        }
        return false;
    }

    private boolean mergeCategory(CategoryDto dto) {
        Optional<Category> opt = categoryRepository.findByClientId(dto.getClientId());
        if (opt.isEmpty()) {
            Category c = new Category();
            copy(dto, c);
            categoryRepository.save(c);
            return true;
        }
        Category existing = opt.get();
        if (dto.getLastModified() != null && dto.getLastModified() > existing.getLastModified()) {
            copy(dto, existing);
            categoryRepository.save(existing);
            return true;
        }
        return false;
    }

    private boolean mergeTransaction(TransactionDto dto) {
        Optional<Transaction> opt = transactionRepository.findByClientId(dto.getClientId());
        if (opt.isEmpty()) {
            Transaction t = new Transaction();
            copy(dto, t);
            transactionRepository.save(t);
            return true;
        }
        Transaction existing = opt.get();
        if (dto.getLastModified() != null && dto.getLastModified() > existing.getLastModified()) {
            copy(dto, existing);
            transactionRepository.save(existing);
            return true;
        }
        return false;
    }

    private boolean mergeBudget(BudgetDto dto) {
        Budget incoming = new Budget();
        copy(dto, incoming);
        Optional<Budget> opt = budgetRepository.findByCategoryClientIdAndYearAndMonth(
                incoming.getCategoryClientId(), incoming.getYear(), incoming.getMonth());
        // 预算的稳定身份是 (categoryClientId, year, month)，而非 clientId，
        // 避免同一预算在两端生成不同 clientId 造成重复。
        Budget target = opt.orElse(null);
        if (target == null) {
            target = incoming;
            budgetRepository.save(target);
            return true;
        }
        if (incoming.getLastModified() > target.getLastModified()) {
            copy(dto, target);
            budgetRepository.save(target);
            return true;
        }
        return false;
    }

    private boolean mergeTemplate(TemplateDto dto) {
        Optional<Template> opt = templateRepository.findByClientId(dto.getClientId());
        if (opt.isEmpty()) {
            Template t = new Template();
            copy(dto, t);
            templateRepository.save(t);
            return true;
        }
        Template existing = opt.get();
        if (dto.getLastModified() != null && dto.getLastModified() > existing.getLastModified()) {
            copy(dto, existing);
            templateRepository.save(existing);
            return true;
        }
        return false;
    }

    private boolean mergeRecurringRule(RecurringRuleDto dto) {
        Optional<RecurringRule> opt = recurringRuleRepository.findByClientId(dto.getClientId());
        if (opt.isEmpty()) {
            RecurringRule r = new RecurringRule();
            copy(dto, r);
            recurringRuleRepository.save(r);
            return true;
        }
        RecurringRule existing = opt.get();
        if (dto.getLastModified() != null && dto.getLastModified() > existing.getLastModified()) {
            copy(dto, existing);
            recurringRuleRepository.save(existing);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  游标分页
    // ------------------------------------------------------------------

    private SyncPullResponse listChanges(long since, long sinceId, boolean ordered) {
        int pageSize = props.getPullPageSize();
        List<Transaction> txns = transactionRepository.keyset(since, sinceId, PageRequest.of(0, pageSize));
        List<Category> cats = categoryRepository.keyset(since, sinceId, PageRequest.of(0, pageSize));
        List<Account> accts = accountRepository.keyset(since, sinceId, PageRequest.of(0, pageSize));
        List<Budget> budgets = budgetRepository.keyset(since, sinceId, PageRequest.of(0, pageSize));
        List<Template> tmpls = templateRepository.keyset(since, sinceId, PageRequest.of(0, pageSize));
        List<RecurringRule> rules = recurringRuleRepository.keyset(since, sinceId, PageRequest.of(0, pageSize));

        SyncPullResponse resp = new SyncPullResponse();
        resp.setServerTime(now());
        txns.forEach(t -> resp.getTransactions().add(TransactionDto.from(t)));
        cats.forEach(c -> resp.getCategories().add(CategoryDto.from(c)));
        accts.forEach(a -> resp.getAccounts().add(AccountDto.from(a)));
        budgets.forEach(b -> resp.getBudgets().add(BudgetDto.from(b)));
        tmpls.forEach(t -> resp.getTemplates().add(TemplateDto.from(t)));
        rules.forEach(r -> resp.getRecurringRules().add(RecurringRuleDto.from(r)));

        boolean hasMore = txns.size() == pageSize || cats.size() == pageSize || accts.size() == pageSize
                || budgets.size() == pageSize || tmpls.size() == pageSize || rules.size() == pageSize;
        resp.setHasMore(hasMore);
        if (hasMore) {
            long maxTs = maxLastModified(txns, cats, accts, budgets, tmpls, rules, since);
            long maxId = txns.isEmpty() ? sinceId : txns.get(txns.size() - 1).getId();
            resp.setNextCursor(encodeCursor(maxTs, maxId));
        }
        return resp;
    }

    private SyncPullResponse listWindow(long from, long until, long fromId) {
        // 简化窗口实现：按 lastModified 分页，上界用 until 过滤。完整实现可复用 keyset。
        int pageSize = props.getPullPageSize();
        SyncPullResponse resp = new SyncPullResponse();
        resp.setServerTime(now());
        // 通过 findAll 后内存过滤不优雅，这里给出可按需扩展的占位：返回 from..until 内的变更。
        // 实际生产建议在 SQL 中直接加 last_modified BETWEEN :from AND :until AND id > :id。
        List<Transaction> txns = transactionRepository.keyset(from, fromId, PageRequest.of(0, pageSize));
        txns.stream().filter(t -> t.getLastModified() <= until)
                .forEach(t -> resp.getTransactions().add(TransactionDto.from(t)));
        resp.setHasMore(txns.size() == pageSize);
        if (!txns.isEmpty()) {
            resp.setNextCursor(encodeCursor(txns.get(txns.size() - 1).getLastModified(), txns.get(txns.size() - 1).getId()));
        }
        // 其它实体同理，占位保留。
        return resp;
    }

    private long maxLastModified(List<Transaction> txns, List<Category> cats, List<Account> accts,
                                 List<Budget> budgets, List<Template> tmpls,
                                 List<RecurringRule> rules, long fallback) {
        long max = fallback;
        for (Transaction t : txns) if (t.getLastModified() > max) max = t.getLastModified();
        for (Category c : cats) if (c.getLastModified() > max) max = c.getLastModified();
        for (Account a : accts) if (a.getLastModified() > max) max = a.getLastModified();
        for (Budget b : budgets) if (b.getLastModified() > max) max = b.getLastModified();
        for (Template t : tmpls) if (t.getLastModified() > max) max = t.getLastModified();
        for (RecurringRule r : rules) if (r.getLastModified() > max) max = r.getLastModified();
        return max;
    }

    private static String encodeCursor(long ts, long id) {
        return Base64.getUrlEncoder().encodeToString((ts + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static long[] decodeCursor(String cursor, long defaultTs) {
        if (cursor == null || cursor.isBlank()) return new long[]{defaultTs, 0L};
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (Exception e) {
            return new long[]{defaultTs, 0L};
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private void copy(AccountDto dto, Account a) {
        a.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        a.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        a.setDeleted(dto.getDeleted() != null ? dto.getDeleted() : false);
        a.setName(dto.getName());
        a.setType(dto.getType());
        a.setIcon(dto.getIcon());
        a.setInitialBalance(dto.getInitialBalance());
        a.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        a.setCreatedAt(a.getCreatedAt() == null ? LocalDateTime.now() : a.getCreatedAt());
        a.setUpdatedAt(LocalDateTime.now());
    }

    private void copy(CategoryDto dto, Category c) {
        c.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        c.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        c.setDeleted(dto.getDeleted() != null ? dto.getDeleted() : false);
        c.setName(dto.getName());
        c.setType(dto.getType());
        c.setIcon(dto.getIcon());
        c.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        c.setCreatedAt(c.getCreatedAt() == null ? LocalDateTime.now() : c.getCreatedAt());
        c.setUpdatedAt(LocalDateTime.now());
    }

    private void copy(TransactionDto dto, Transaction t) {
        t.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        t.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        t.setDeleted(dto.getDeleted() != null ? dto.getDeleted() : false);
        t.setDate(dto.getDate());
        t.setAmount(dto.getAmount());
        t.setType(dto.getType());
        t.setCategoryClientId(dto.getCategoryClientId());
        t.setAccountClientId(dto.getAccountClientId());
        t.setNote(dto.getNote());
        t.setPhotoPath(dto.getPhotoPath());
        t.setSource(dto.getSource());
        t.setCreatedAt(t.getCreatedAt() == null ? LocalDateTime.now() : t.getCreatedAt());
        t.setUpdatedAt(LocalDateTime.now());
    }

    private void copy(BudgetDto dto, Budget b) {
        b.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        b.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        b.setDeleted(dto.getDeleted() != null ? dto.getDeleted() : false);
        b.setCategoryClientId(dto.getCategoryClientId());
        b.setYear(dto.getYear());
        b.setMonth(dto.getMonth());
        b.setAmountLimit(dto.getAmountLimit());
        b.setCreatedAt(b.getCreatedAt() == null ? LocalDateTime.now() : b.getCreatedAt());
        b.setUpdatedAt(LocalDateTime.now());
    }

    private void copy(TemplateDto dto, Template t) {
        t.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        t.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        t.setDeleted(dto.getDeleted() != null ? dto.getDeleted() : false);
        t.setName(dto.getName());
        t.setAmount(dto.getAmount());
        t.setType(dto.getType());
        t.setCategoryClientId(dto.getCategoryClientId());
        t.setAccountClientId(dto.getAccountClientId());
        t.setNote(dto.getNote());
        t.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        t.setCreatedAt(t.getCreatedAt() == null ? LocalDateTime.now() : t.getCreatedAt());
        t.setUpdatedAt(LocalDateTime.now());
    }

    private void copy(RecurringRuleDto dto, RecurringRule r) {
        r.setClientId(dto.getClientId() != null ? dto.getClientId() : UUID.randomUUID().toString());
        r.setLastModified(dto.getLastModified() != null ? dto.getLastModified() : System.currentTimeMillis());
        r.setDeleted(dto.getDeleted() != null ? dto.getDeleted() : false);
        r.setName(dto.getName());
        r.setAmount(dto.getAmount());
        r.setType(dto.getType());
        r.setCategoryClientId(dto.getCategoryClientId());
        r.setAccountClientId(dto.getAccountClientId());
        r.setNote(dto.getNote());
        r.setIntervalUnit(dto.getIntervalUnit() == null ? "MONTH" : dto.getIntervalUnit());
        r.setIntervalCount(dto.getIntervalCount() == null || dto.getIntervalCount() < 1
                ? 1 : dto.getIntervalCount());
        r.setAnchorDate(dto.getAnchorDate() != null ? dto.getAnchorDate() : LocalDate.now());
        r.setLastGeneratedDate(dto.getLastGeneratedDate());
        r.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        r.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        r.setCreatedAt(r.getCreatedAt() == null ? LocalDateTime.now() : r.getCreatedAt());
        r.setUpdatedAt(LocalDateTime.now());
    }
}
