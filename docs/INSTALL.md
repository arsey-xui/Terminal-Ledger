# 零基础安装指南（手机端 + 桌面端）

本项目是**源码工程**，还没有打包成“双下载即装”的安装包。所以安装过程就是“在电脑上打开开发工具，把工程跑起来”。跟着做即可，不需要懂编程。

> 也可以在 GitHub **Releases** 里下载已打包好的产物（若有）：桌面端 `TerminalLedger.exe`（免安装、自带运行环境）、手机端 APK。

## 0. 你要装哪几样？

| 端 | 需要装什么 | 难度 |
| --- | --- | --- |
| 桌面端（JavaFX） | JDK 17 + Maven | ★★☆ |
| 手机端（Android） | Android Studio（自带 JDK） | ★★★ |
| 云端后端（可选） | MySQL + Spring Boot | ★★★（**只有想“手机↔电脑同步”才需要**） |

> 只想本机记账、不看同步 → **先别装后端**，桌面端/手机端都支持离线使用。
> 想手机和电脑数据互通 → 才需要把上面的后端也跑起来。

仓库结构：

```
<仓库根目录>
├── desktop\   电脑端（JavaFX）
├── android\   手机端（Android）
├── backend\   云端后端（Spring Boot）
└── sql\init.sql  建库脚本
```

---

## 一、桌面端（最简单，建议先做）

### 第 1 步：装 JDK 17

1. 打开浏览器访问：`https://adoptium.net/temurin/releases/?version=17`
2. 系统选 **Windows x64**（或对应平台），下载 `.msi`，双击安装，一路“下一步”。
3. 装完**重开**一个命令行窗口，输入 `java -version`，能看到 `17.x` 即成功。

### 第 2 步：装 Maven

1. 访问 `https://maven.apache.org/download.cgi`，下载 `apache-maven-3.9.x-bin.zip`。
2. 解压到比如 `C:\tools\apache-maven-3.9.x`。
3. 环境变量：系统 `Path` 里新增 `C:\tools\apache-maven-3.9.x\bin`。
4. 重开命令行，输入 `mvn -v`，能打印版本即成功。

### 第 3 步：启动桌面端

1. 进入桌面端目录：
   ```
   cd <仓库根目录>\desktop
   ```
2. 运行：
   ```
   mvn javafx:run
   ```
3. 首次运行 Maven 会下载依赖（几分钟），然后弹出“终端账本”窗口。
4. 首次使用会在 `%USERPROFILE%\accounting` 里自动生成 H2 数据库文件（`accounting.mv.db`）和设备号 `device.id`。
5. 默认是“仪表盘”，左侧栏有：仪表盘 / 记账 / 账户 / 预算 / 数据管理。

> 此时就能录入、看报表、设置预算了——**完全离线可用**。

> 提示：如果 Maven Central 拉取失败，可先用仓库里的 `maven-settings-local.xml`（含镜像）：
> `mvn -s ..\maven-settings-local.xml javafx:run`

---

## 二、手机端（Android）

### 第 1 步：装 Android Studio

1. 访问 `https://developer.android.com/studio`，下载对应平台版本并安装。
2. 安装时按默认继续（会占几 GB 空间）。
3. 首次启动选 **Standard**，等待下载完成。

### 第 2 步：打开工程

在 Android Studio 里 **File → Open**，选择本仓库的 `android` 文件夹。第一次打开会让 Gradle 下载依赖，耐心等待（几分钟到十几分钟，属正常）。

### 第 3 步：设置后端地址（决定“连不连云端”）

打开 `android/app/src/main/java/com/personal/accounting/android/sync/SyncConfig.java`，把 `BASE_URL` 改成：

- **模拟器**：保持默认 `http://10.0.2.2:8080`（模拟器把本机映射成 10.0.2.2）。
- **真机**：改成电脑的局域网 IP，例如 `http://192.168.1.100:8080`（手机和电脑要连**同一个 Wi-Fi**，后端已启动并放行 8080 端口）。

```java
public static String BASE_URL = "http://10.0.2.2:8080";   // 模拟器
// public static String BASE_URL = "http://192.168.1.100:8080"; // 真机
```

### 第 4 步：运行

1. 点工具栏绿色 **Run ▶**。
2. 选**模拟器**或 **USB 连接真机**（真机需开启“开发者选项 → USB 调试”，首次连接手机上点“允许”）。
3. App 启动后默认是“记账”页。
4. App 内“设置 → 云端地址”也能直接改后端地址（优先于改源码）。

> 提示：如果电脑开不了模拟器（报虚拟化错误），直接用真机 USB 跑通常更快。

---

## 三、云端后端（仅“手机↔电脑同步”需要）

想让手机和桌面端数据互通，先让后端跑起来。

### 第 1 步：装 MySQL 8 并建库

1. 安装 MySQL 8，记住 root 密码。
2. 打开命令行，执行（会问你 root 密码）：
   ```
   mysql -u root -p < <仓库根目录>\sql\init.sql
   ```
   这会创建 `accounting` 库和表，并写入示例数据。

### 第 2 步：设置环境变量（脱敏配置）

在 PowerShell 设置：
```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/accounting?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="你的MySQL密码"
$env:SYNC_TOKEN_SECRET="任意的长字符串，至少32位"
```

### 第 3 步：启动后端

```
cd <仓库根目录>\backend
mvn spring-boot:run
```

看到 `Started AccountingApplication` 后，后端就在 `http://localhost:8080` 运行。

> 也可以用 Docker 一键起（含 MySQL + 后端）：仓库根目录执行 `cp .env.example .env` 后 `docker compose up -d`。

### 第 4 步：让两端去连它

- **桌面端**：`desktop/.../sync/SyncConfig.java` 的 `BASE_URL` 默认 `http://localhost:8080`，本机即可。
- **手机端**：按上文改成电脑局域网 IP；手机与电脑同 Wi-Fi。
- 桌面端“数据管理 → 立即同步”、手机端“设置 → 立即同步”，触发增量同步，冲突按“最后修改时间大的赢（LWW）”自动合并。

---

## 四、常见问题排查

| 现象 | 原因/解决 |
| --- | --- |
| `java -version` 报“不是内部或外部命令” | JDK 没装好或没重开窗口，重装/重开命令行。 |
| `mvn -v` 报错 | Path 里那个 bin 路径没加对，或没重开窗口。 |
| Android Studio 一直下载依赖 | 首次很慢，等即可；可切换国内镜像源。 |
| 模拟器起不来 | 开 Hyper-V/或直接用真机 USB。 |
| 手机连不上后端 | 同一 Wi-Fi；后端已启动且防火墙放行 8080；`BASE_URL` 用电脑局域网 IP；记得带 `http://`。 |
| 桌面端连不上后端 | 桌面端与后端要在同一台电脑，或把桌面端 `BASE_URL` 改成后端所在机器的 IP。 |
| MySQL 连不上 | 核对 `DB_URL/DB_USERNAME/DB_PASSWORD`，数据库名必须是 `accounting`。 |
| 同步没反应 | 先在“设置/数据管理”里点“立即同步”，看有没有报错；确认后端地址正确。 |

---

## 五、不想装开发环境？

用一键打包脚本即可产出“免装”产物（见 [BUILD-AND-RELEASE.md](BUILD-AND-RELEASE.md)）：

```powershell
.\scripts\build-desktop-installer.ps1          # 桌面端 -> outputs\TerminalLedger\TerminalLedger.exe
.\scripts\build-android-release.ps1            # 手机端 -> outputs\apk\TerminalLedger-v0.1.0.apk
docker compose up -d                            # 后端（含 MySQL）
```
