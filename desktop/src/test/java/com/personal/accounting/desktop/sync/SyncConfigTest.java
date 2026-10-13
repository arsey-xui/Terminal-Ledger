package com.personal.accounting.desktop.sync;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后端地址的读写逻辑。
 *
 * <p>回归背景：桌面端点「保存后端地址」报
 * {@code 保存失败: C:\Users\34803\accounting\sync.properties} ——
 * 那是 {@code AccessDeniedException} 的全部信息（Java 的文件系统异常只带路径）。
 * 根因是写盘被运行环境挡住（受限身份 / 低完整性级别）。但**用户往往什么都没改**：
 * 输入框预填的就是磁盘上已有的值，点一下保存却弹错，连带把同步也拖住了。</p>
 *
 * <p>两个必须注意的点：</p>
 * <ul>
 *   <li>{@code DATA_DIR}/{@code FILE} 是 {@code static final}，只在类首次加载时求值 ——
 *       所以 {@code user.home} 必须在触碰 {@link SyncConfig} **之前**改掉
 *       （{@code @BeforeAll} 早于类初始化，够用），否则测试会写到用户真实的账本目录里。</li>
 *   <li>不能伪造「另一个 user.home」去构造写失败：路径早已固定，断言会落在别的路径上，
 *       看着通过其实什么都没验到（这个测试曾经就这么错过一次）。</li>
 * </ul>
 */
class SyncConfigTest {

    @TempDir
    static Path fakeHome;

    @BeforeAll
    static void isolateUserHome() {
        // 必须在引用 SyncConfig 之前执行，否则 DATA_DIR 已经指向真实 ~/accounting
        System.setProperty("user.home", fakeHome.toString());
    }

    private Path file() {
        return SyncConfig.dataDir().resolve("sync.properties");
    }

    @BeforeEach
    void reset() throws Exception {
        Files.deleteIfExists(file());
        SyncConfig.BASE_URL = "";
        Files.createDirectories(SyncConfig.dataDir());
    }

    /** 未被本轮测试用过的地址，保证不会命中「值没变就跳过」的分支。 */
    private String freshUrl() {
        return "http://probe-" + System.nanoTime() + ":8080";
    }

    private String readStoredBaseUrl() throws Exception {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(file())) {
            p.load(r);
        }
        return p.getProperty("base_url");
    }

    @Test
    void writesIntoIsolatedDirectoryNotTheRealOne() {
        // 防呆：确认真的没在写用户的账本目录
        assertEquals(fakeHome.resolve("accounting"), SyncConfig.dataDir());
    }

    @Test
    void changedValueIsWrittenAndCanBeReadBack() throws Exception {
        String url = freshUrl();
        SyncConfig.saveBaseUrl(url);

        assertTrue(Files.exists(file()), "值变了必须落盘");
        assertEquals(url, readStoredBaseUrl(), "落盘内容应与写入一致");

        // 模拟重启：清空内存值再从磁盘读回来
        SyncConfig.BASE_URL = "";
        SyncConfig.loadBaseUrl();
        assertEquals(url, SyncConfig.BASE_URL, "重启后要能读回同一个地址");
    }

    @Test
    void unchangedValueSkipsWriteAndStillSucceeds() throws Exception {
        String url = freshUrl();
        SyncConfig.saveBaseUrl(url);
        assertTrue(Files.exists(file()));

        // 删掉文件再存同一个值：走了「跳过写盘」分支的话，文件不会重新出现、
        // 也不会因为写不进去而抛异常 —— 这正是用户点开设置直接点保存时的情形。
        Files.delete(file());
        SyncConfig.saveBaseUrl(url);

        assertFalse(Files.exists(file()), "值没变就不该写盘");
        assertEquals(url, SyncConfig.BASE_URL, "内存里的值仍要正确");
    }

    @Test
    void loadIgnoresMissingFileAndKeepsInMemoryValue() throws Exception {
        SyncConfig.BASE_URL = "http://keep-me:8080";
        Files.deleteIfExists(file());
        SyncConfig.loadBaseUrl();
        assertEquals("http://keep-me:8080", SyncConfig.BASE_URL, "文件不存在时不应清空内存里的值");
    }

    @Test
    void loadTrimsValueAndIgnoresOtherKeys() throws Exception {
        Properties p = new Properties();
        p.setProperty("base_url", "  http://10.0.0.7:8080  ");
        p.setProperty("unrelated", "x");
        try (var w = Files.newBufferedWriter(file())) {
            p.store(w, "同步配置");
        }

        SyncConfig.BASE_URL = "";
        SyncConfig.loadBaseUrl();
        assertEquals("http://10.0.0.7:8080", SyncConfig.BASE_URL, "读出来的地址应去掉首尾空白");
    }
}
