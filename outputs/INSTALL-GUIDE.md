# 零基础安装指南（手机端 + 桌面端）

先了解一件事：本项目是**源码工程**，还没有打包成“双下载即装”的安装包。所以安装过程就是“在电脑上打开开发工具，把工程跑起来”。只要跟着做，任何一个新手都能完成。

## 0. 你要装哪几样？

| 端 | 需要装什么 | 难度 |
| --- | --- | --- |
| 桌面端（JavaFX） | JDK 17 + Maven | ★★☆ |
| 手机端（Android） | Android Studio（自带 JDK） | ★★★ |
| 云端后端（可选） | MySQL + Spring Boot | ★★★（**只有想“手机↔电脑同步”才需要**） |

> 只想在本机记账、不看同步 → **先别装后端**，桌面端/手机端都支持离线使用。
> 想手机和电脑数据互通 → 才需要把上面的后端也跑起来。

本项目在电脑上的位置（你本地已生成）：

```
C:\Users\34803\Documents\Codex\2026-08-22\java-android-javafx-spring-boot-github
├── desktop\   电脑端（JavaFX）
├── android\   手机端（Android）
├── backend\   云端后端（Spring Boot）
└── sql\init.sql  建库脚本
```

---

## 一、桌面端安装（最简单，建议先做）

### 第 1 步：装 JDK 17

1. 打开浏览器，访问：`https://adoptium.net/temurin/releases/?version=17`
2. 系统选 **Windows x64**，下载 `.msi`，双击安装，一路“下一步”。
3. 安装完**重开**一个命令行窗口，输入：
   ```
   java -version
   ```
   能看到 `17.x` 就说明成功。

### 第 2 步：装 Maven

1. 访问 `https://maven.apache.org/download.cgi`，下载 `apache-maven-3.9.x-bin.zip`。
2. 解压到比如 `C:\tools\apache-maven-3.9.x`。
3. 打开“环境变量”：开始菜单搜“环境变量” → 编辑系统变量 `Path` → 新建，填
   `C:\tools\apache-maven-3.9.x\bin`。
4. 重开命令行，输入：
   ```
   mvn -v
   ```
   能打印版本即成功。

### 第 3 步：启动桌面端

1. 打开命令行，进入桌面端目录：
   ```
   cd C:\Users\34803\Documents\Codex\2026-08-22\java-android-javafx-spring-boot-github\desktop
   ```
2. 运行：
   ```
   mvn -s ..\maven-settings-local.xml javafx:run
   ```
3. 首次运行 Maven 会下载依赖（几分钟），然后弹出“个人记账 · 电脑端”窗口。
4. 首次使用会在 `C:\Users\你的用户名\accounting` 里自动生成 H2 数据库文件（`accounting.mv.db`）和设备号 `device.id`，**这类文件已被 `.gitignore` 忽略，不会提交到仓库**。
5. 一进来默认是“仪表盘”，左边栏有：仪表盘 / 记账入口 / 账户管理 / 预算管理 / 数据管理。

> 此时就能录入、看报表、设置预算了——**完全离线可用**。

---

## 二、手机端安装（Android）

### 第 1 步：装 Android Studio

1. 访问 `https://developer.android.com/studio`，下载 Windows 版，双击安装。
2. 安装过程中如果问是否安装 SDK / 模拟器，**按默认继续**（会占几 GB 空间）。
3. 首次启动选 **Standard**，等待下载完成。

### 第 2 步：打开工程

1. Android Studio 里点 **File → Open**，选择本目录下的 `android` 文件夹。
2. 第一次打开会让 Gradle 下载依赖，**耐心等待**（几分钟到十几分钟，属正常）。

### 第 3 步：设置后端地址（决定“连不连云端”）

打开文件：
`android/app/src/main/java/com/personal/accounting/android/sync/SyncConfig.java`

把 `BASE_URL` 改成：

- **模拟器**：保持默认 `http://10.0.2.2:8080`（模拟器把本机映射成 10.0.2.2）。
- **真机**：改成电脑的局域网 IP，例如 `http://192.168.1.100:8080`（手机和电脑要连**同一个 Wi-Fi**，后端要已启动并放行 8080 端口）。

```java
public static String BASE_URL = "http://10.0.2.2:8080";   // 模拟器
// public static String BASE_URL = "http://192.168.1.100:8080"; // 真机
```

### 第 4 步：运行

1. 点工具栏绿色 **Run ▶**。
2. 选一个**模拟器**，或用 **USB 连接真机**（真机需开启“开发者选项 → USB 调试”，首次连接手机上要点“允许”）。
3. App 启动后默认是“记账”页，底部三个 Tab：**记账 / 报表 / 设置**。

> 零基础提示：如果电脑开不了模拟器（报错虚拟化），就直接用真机连 USB 跑，通常更快。

---

## 三、云端后端（仅“手机↔电脑同步”需要）

想让手机和桌面端数据互通，先让后端跑起来。

### 第 1 步：装 MySQL 8 并建库

1. 安装 MySQL 8，记住 root 密码。
2. 打开命令行，执行（会问你 root 密码）：
   ```
   mysql -u root -p < C:\Users\34803\Documents\Codex\2026-08-22\java-android-javafx-spring-boot-github\sql\init.sql
   ```
   这会创建 `accounting` 库和表，并写入示例数据。

### 第 2 步：设置环境变量（脱敏配置）

在命令行（PowerShell）设置：
```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/accounting?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="你的MySQL密码"
$env:SYNC_TOKEN_SECRET="任意的长字符串，至少32位"
```

### 第 3 步：启动后端

```
cd C:\Users\34803\Documents\Codex\2026-08-22\java-android-javafx-spring-boot-github\backend
mvn spring-boot:run
```

看到 `Started AccountingApplication` 后，后端就在 `http://localhost:8080` 运行。

### 第 4 步：让两端去连它

- **桌面端**：`desktop/.../sync/SyncConfig.java` 的 `BASE_URL` 默认 `http://localhost:8080`，本机即可。
- **手机端**：按上文改成电脑局域网 IP；手机与电脑同 Wi-Fi。
- 桌面端“数据管理 → 立即同步”、手机端“设置 → 立即同步”，即触发增量同步，冲突自动按“最后修改时间大的赢（LWW）”合并。

---

## 四、常见问题排查

| 现象 | 原因/解决 |
| --- | --- |
| `java -version` 报“不是内部或外部命令” | JDK 没装好或没重开窗口，重装/重开命令行。 |
| `mvn -v` 报错 | Path 里那个 bin 路径没加对，或没重开窗口。 |
| Android Studio 一直下载依赖 | 首次很慢，等即可；可切换国内镜像源。 |
| 模拟器起不来 | 开 Hyper-V/或直接用真机 USB。 |
| 手机连不上后端 | 同一 Wi-Fi；后端已启动且防火墙放行 8080；`BASE_URL` 用的是电脑局域网 IP；记得带 `http://`。 |
| 桌面端连不上后端 | 桌面端和后台要在同一台电脑，或把桌面端 `BASE_URL` 也改成后端所在机器的 IP。 |
| MySQL 连不上 | 核对 `DB_URL/DB_USERNAME/DB_PASSWORD`，数据库名必须是 `accounting`。 |
| 同步没反应 | 先在“设置/数据管理”里点“立即同步”，看有没有报错；确认后端地址正确。 |

---

## 五、想更省事？可以再帮你打包

如果你不想装 JDK/Maven/Android Studio，我可以继续帮你：

- **桌面端** → 生成“双击即用”的打包版/安装器（jlink + jpackage）。
- **手机端** → 打出 **release APK**，直接传到手机安装。
- **后端** → 出一份 **Docker Compose**（MySQL + 后端一条命令起）。

做到那样，零基础就真的只需“双击/扫码”了。要不要现在做？
