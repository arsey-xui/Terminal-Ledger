package com.personal.accounting.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.Template;
import com.personal.accounting.desktop.model.Transaction;
import com.personal.accounting.desktop.sync.Json;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 数据管理：CSV 导出 + 加密备份包（.zip + AES-256-GCM）一键导入/恢复。
 * 恢复时覆盖当前数据，需调用方二次确认。
 */
public class BackupService {

    private static final ObjectMapper MAPPER = Json.MAPPER;
    private static final int SALT_LEN = 16;

    public static class BackupPayload {
        public List<Transaction> transactions;
        public List<Category> categories;
        public List<Account> accounts;
        public List<Budget> budgets;
        public List<Template> templates;
        public long exportedAt;
    }

    // ---------- CSV ----------
    public static void exportCsv(Path path, List<Transaction> txns) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("日期,类型,金额,分类,账户,备注,来源,clientId,lastModified\n");
        for (Transaction t : txns) {
            sb.append(esc(t.date == null ? "" : t.date.toString())).append(',')
                    .append(esc(t.type)).append(',')
                    .append(t.amount).append(',')
                    .append(esc(t.categoryClientId)).append(',')
                    .append(esc(t.accountClientId)).append(',')
                    .append(esc(t.note)).append(',')
                    .append(esc(t.source)).append(',')
                    .append(esc(t.clientId)).append(',')
                    .append(t.lastModified).append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---------- 加密备份包 ----------
    public static void createBackup(Path zipPath, DataManager db, char[] password) throws Exception {
        BackupPayload p = new BackupPayload();
        p.transactions = db.listTransactions(null, null, null, null);
        p.categories = db.listCategories("EXPENSE");
        p.categories.addAll(db.listCategories("INCOME"));
        p.accounts = db.listAccounts();
        p.budgets = db.listAllBudgets();
        p.exportedAt = System.currentTimeMillis();

        // 1) 把全量数据序列化，写进一个 zip（可包含照片目录）
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("backup.json"));
            zos.write(MAPPER.writeValueAsBytes(p));
            zos.closeEntry();
        }
        byte[] plain = baos.toByteArray();

        // 2) 对整个 zip 做 AES-256-GCM 加密
        byte[] encrypted = encrypt(plain, password);
        Files.write(zipPath, encrypted);
    }

    @SuppressWarnings("unchecked")
    public static void restoreBackup(Path zipPath, DataManager db, char[] password) throws Exception {
        byte[] encrypted = Files.readAllBytes(zipPath);
        byte[] plain = decrypt(encrypted, password);
        BackupPayload p = MAPPER.readValue(plain, BackupPayload.class);

        // 覆盖当前数据
        for (Account a : nullSafe(p.accounts)) db.upsertAccount(a);
        for (Category c : nullSafe(p.categories)) db.upsertCategory(c);
        for (Transaction t : nullSafe(p.transactions)) db.upsertTransaction(t);
        for (Budget b : nullSafe(p.budgets)) db.upsertBudget(b);
        for (Template t : nullSafe(p.templates)) db.upsertTemplate(t);
    }

    private static <T> List<T> nullSafe(List<T> x) {
        return x == null ? List.of() : x;
    }

    private static byte[] encrypt(byte[] data, char[] password) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(salt);
        out.write(iv);
        out.write(ct);
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] enc, char[] password) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(enc, 0, salt, 0, SALT_LEN);
        byte[] iv = new byte[12];
        System.arraycopy(enc, SALT_LEN, iv, 0, 12);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(enc, SALT_LEN + 12, enc.length - SALT_LEN - 12);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 65536, 256);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] dk = skf.generateSecret(spec).getEncoded();
        return new SecretKeySpec(dk, "AES");
    }
}
