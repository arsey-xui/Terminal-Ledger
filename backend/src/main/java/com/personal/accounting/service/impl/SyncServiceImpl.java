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
        boolean firstPage = request.getCursor() == null || request.getCursor().isBlank();

        // 字典类（分类 / 账户 / 模板 / 周期规则 / 预算）**不套时间窗**，永远按客户端水位拉：
        // 首次同步时水位是 0，也就是全量。这些表条数少，而且几个月前创建之后再没改过的分类、
        // 账户必须能被新设备拿到 —— 否则流水里的外键会悬空，列表显示成「未分类」。
        long dictSince = since;

        // 只有流水走窗口：首次同步只拉最近 N 个月，避免一次拉全量。
        long txnSince = since;
        if (fullSync && firstPage && props.getPullWindowMonths() != null) {
            txnSince = now() - java.util.concurrent.TimeUnit.DAYS.toMillis(props.getPullWindowMonths() * 30L);
        }
        return listChanges(startCursor(request.getCursor(), txnSince, dictSince));
    }

    /**
     * 算出这一页每个实体的起拉位置：有游标就按游标（每个实体各自一对），没有就是第一页 ——
     * 流水从窗口起点、字典类从客户端水位开始，id 段留 0 以包含所有记录。
     */
    private static SyncCursor startCursor(String raw, long txnSince, long dictSince) {
        SyncCursor stored = decodeCursor(raw);
        if (stored != null) return stored;
        SyncCursor c = new SyncCursor();
        c.transaction[0] = txnSince;
        c.category[0] = dictSince;
        c.account[0] = dictSince;
        c.budget[0] = dictSince;
        c.template[0] = dictSince;
        c.recurringRule[0] = dictSince;
        return c;
    }

    @Override
    @Transactional(readOnly = true)
    public SyncPullResponse pullWindow(SyncPullRequest request) {
        long from = request.getSince() == null ? 0L : request.getSince();
        long until = request.getUntil() == null ? now() : request.getUntil();
        SyncCursor stored = decodeCursor(request.getCursor());
        SyncCursor start = stored != null ? stored : new SyncCursor();
        if (stored == null) start.transaction[0] = from;
        return listWindow(start, until);
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

    /**
     * 一页变更。每个实体按**自己的**游标段查询，互不影响。
     *
     * @param cursor 这一页每个实体的起拉位置（见 {@link SyncCursor}）
     */
    private SyncPullResponse listChanges(SyncCursor cursor) {
        int pageSize = props.getPullPageSize();
        PageRequest page = PageRequest.of(0, pageSize);
        List<Transaction> txns = transactionRepository.keyset(cursor.transaction[0], cursor.transaction[1], page);
        List<Category> cats = categoryRepository.keyset(cursor.category[0], cursor.category[1], page);
        List<Account> accts = accountRepository.keyset(cursor.account[0], cursor.account[1], page);
        List<Budget> budgets = budgetRepository.keyset(cursor.budget[0], cursor.budget[1], page);
        List<Template> tmpls = templateRepository.keyset(cursor.template[0], cursor.template[1], page);
        List<RecurringRule> rules = recurringRuleRepository.keyset(
                cursor.recurringRule[0], cursor.recurringRule[1], page);

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
            // 每个实体各自推进自己的游标：满页的接在最后一条之后，没满页的标记成「已取完」
            SyncCursor next = new SyncCursor();
            advance(next.transaction, txns, pageSize);
            advance(next.category, cats, pageSize);
            advance(next.account, accts, pageSize);
            advance(next.budget, budgets, pageSize);
            advance(next.template, tmpls, pageSize);
            advance(next.recurringRule, rules, pageSize);
            resp.setNextCursor(encodeCursor(next));
        }
        return resp;
    }

    private SyncPullResponse listWindow(SyncCursor start, long until) {
        // 简化窗口实现：按 lastModified 分页，上界用 until 过滤。完整实现可复用 keyset。
        int pageSize = props.getPullPageSize();
        SyncPullResponse resp = new SyncPullResponse();
        resp.setServerTime(now());
        List<Transaction> txns = transactionRepository.keyset(start.transaction[0], start.transaction[1],
                PageRequest.of(0, pageSize));
        txns.stream().filter(t -> t.getLastModified() <= until)
                .forEach(t -> resp.getTransactions().add(TransactionDto.from(t)));
        boolean hasMore = txns.size() == pageSize;
        resp.setHasMore(hasMore);
        if (hasMore) {
            // 只回补流水。其余五段钉成「已取完」，免得客户端拿这个游标回头调 pull 时把字典类从头再拉一遍。
            SyncCursor next = new SyncCursor();
            advance(next.transaction, txns, pageSize);
            pinExhausted(next.category, next.account, next.budget, next.template, next.recurringRule);
            resp.setNextCursor(encodeCursor(next));
        }
        // 其它实体同理，占位保留。
        return resp;
    }

    /**
     * 一页的续拉位置：**每个实体各自一对 (lastModified, id)**。
     *
     * <p>为什么不能六张表共用一个 (ts, id)：{@code id} 是各表自己的自增序列，拿流水的 id 去筛分类，
     * 在「本页只有分类、流水没满页」时会把游标卡在原地（无限返回同一页），
     * id 大小对不上时还会跳过记录。keyset 查询的判定本身是
     * {@code last_modified > :ts or (last_modified = :ts and id > :id)}，天然就是每表一对。</p>
     */
    private static final class SyncCursor {
        final long[] transaction = {0L, 0L};
        final long[] category = {0L, 0L};
        final long[] account = {0L, 0L};
        final long[] budget = {0L, 0L};
        final long[] template = {0L, 0L};
        final long[] recurringRule = {0L, 0L};

        /** 编码顺序固定：流水、分类、账户、预算、模板、周期规则。 */
        long[][] segments() {
            return new long[][]{transaction, category, account, budget, template, recurringRule};
        }
    }

    /**
     * 推进某个实体的游标：满页就接在最后一条之后；没满页说明这个实体已经取完，
     * 钉成 (MAX, MAX) 让后面的页不再重复扫它。
     */
    private static void advance(long[] segment, List<? extends BaseEntity> rows, int pageSize) {
        if (rows.size() < pageSize) {
            pinExhausted(segment);
            return;
        }
        BaseEntity last = rows.get(rows.size() - 1);
        segment[0] = last.getLastModified();
        segment[1] = last.getId();
    }

    /** 把游标段标记为「这个实体已经取完」。 */
    private static void pinExhausted(long[]... segments) {
        for (long[] segment : segments) {
            segment[0] = Long.MAX_VALUE;
            segment[1] = Long.MAX_VALUE;
        }
    }

    private static String encodeCursor(SyncCursor cursor) {
        StringBuilder sb = new StringBuilder();
        for (long[] segment : cursor.segments()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(segment[0]).append(':').append(segment[1]);
        }
        return Base64.getUrlEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解游标；空或格式不对返回 null（调用方按第一页处理，坏游标不会让客户端卡在循环里）。
     *
     * <p>兼容旧格式：旧版游标只有一段 {@code ts:id}、六张表共用。它只会出现在「一次 pull 循环
     * 中途碰上服务端升级」这种情形，按旧语义铺到六段上即可，不值得为它中断同步。</p>
     */
    private static SyncCursor decodeCursor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String text = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = text.split(",");
            SyncCursor cursor = new SyncCursor();
            long[][] segments = cursor.segments();
            if (parts.length == 1) {
                long[] pair = parsePair(parts[0]);
                if (pair == null) return null;
                for (long[] segment : segments) {
                    segment[0] = pair[0];
                    segment[1] = pair[1];
                }
                return cursor;
            }
            if (parts.length != segments.length) return null;
            for (int i = 0; i < segments.length; i++) {
                long[] pair = parsePair(parts[i]);
                if (pair == null) return null;
                segments[i][0] = pair[0];
                segments[i][1] = pair[1];
            }
            return cursor;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析一段 {@code ts:id}；格式不对返回 null。 */
    private static long[] parsePair(String text) {
        int sep = text.indexOf(':');
        if (sep <= 0 || sep == text.length() - 1) return null;
        try {
            return new long[]{Long.parseLong(text.substring(0, sep)), Long.parseLong(text.substring(sep + 1))};
        } catch (NumberFormatException e) {
            return null;
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
