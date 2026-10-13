# 构建与发布（桌面包 / APK / Docker）

> 本仓库在无 Maven/Gradle/Android SDK 的环境中生成。请在任何**装有 JDK 17 + Maven**（桌面板、后端）或 **Android Studio**（手机端）并**可联网**的机器上执行即可产出最终产物。

## 0. 三个产物一览

| 产物 | 构建设置 | 一键命令 | 产物位置 |
| --- | --- | --- | --- |
| 后端容器 | `docker-compose.yml` + `backend/Dockerfile` | `docker compose up -d` | 运行中的服务 |
| 桌面包 | `desktop/pom.xml`(shade) + `scripts/build-desktop-installer.ps1` | `.\scripts\build-desktop-installer.ps1` | `outputs\TerminalLedger\TerminalLedger.exe` |
| Android APK | `android/app/build.gradle`(签名) + `scripts/build-android-release.ps1` | `.\scripts\build-android-release.ps1 -GenerateKeystore` | `outputs\apk\TerminalLedger-v0.1.0.apk` |

> **重要：`outputs\TerminalLedger` 里的 exe 不适合直接运行。**
> 它的位置属于「构建输出目录」，机器上可能有完整性级别限制（Windows 的
> `Mandatory Label\Low Mandatory Level` + No-Write-Up）。从那里启动的程序会继承低完整性令牌，
> **写不了用户目录**：表现为「设置后端地址保存失败」，以及更隐蔽的
> `org.h2.jdbc.JdbcSQLNonTransientException: The database is read only`
> ——本地库只能只读打开，同步一写就失败。
>
> 安装/使用时请把整个 `TerminalLedger` 文件夹**用资源管理器复制到普通位置**（桌面、
> `%LOCALAPPDATA%\Programs`、`D:\Apps` 等），再运行里面的 exe。用资源管理器复制或从 zip 解压出来的
> 文件不会带那个标签；可用 `icacls 文件夹 /t | findstr "Mandatory Label"` 确认（输出为空即正常）。
> 数据目录仍是 `~/accounting`，换位置不影响账本。
>
> **不想搬位置时的就地修复**（管理员 PowerShell）：把这个文件夹的完整性级别提回 Medium，
> 之后从原位置直接运行也能写数据。
>
> ```powershell
> icacls outputs\TerminalLedger /t /setintegritylevel M
> ```
>
> 两个注意点：一是**每次重新打包都会重建这个目录、标签随之丢失**，需要重跑一次；
> 二是工作区根目录本身带 Low 标签并向下继承，所以只对 `outputs\TerminalLedger` 单独提级即可，
> 不要把工作区根也提上去 —— 那会让沙箱内的构建进程反而写不进工作区（No-Write-Up 是双向的）。

### 构建目录的完整性标签（本地开发时会踩到）

完整性标签会**双向**挡人：低完整性进程写不进高完整性的对象，高完整性进程写不进低完整性的对象
（后者只在对方是显式 Low 时发生）。在本机同时用「沙箱内的工具」和「普通权限的编辑器」改这个仓库时，
`target/`、`build/` 这类构建目录会出现两种现象：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 报「写不出 `Xxx.class`」「写不出 `createdFiles.lst`」 | 构建目录里的文件是另一个完整性级别的进程生成的 | 删掉该构建目录重新生成（`desktop\target`、`backend\target`） |
| Gradle 报 `gradle-*-bin.zip.lck 拒绝访问` | 缓存目录缺少 Low 标签 | 对整个工作区重跑一次 `icacls <工作区> /t /c /setintegritylevel L` |

新目录**不会自动继承**父目录的标签，所以 Maven/Gradle 新建的目录会落在默认的 Medium，
低完整性的构建进程就写不进去。删掉重建即可恢复（重建出来的目录会带上父目录的标签）。

---

## 1. 后端 Docker 一键启动

```bash
cp .env.example .env
docker compose up -d
```

- 首次会拉取 `mysql:8.0`、用 Maven 构建后端镜像（需联网）。
- 自动通过 `sql/init.sql` 初始化 `accounting` 库与 Demo 数据。
- 后端地址：`http://localhost:8080`；健康检查 `GET /api/health`。
- 停止：`docker compose down`；连同数据库数据清理：`docker compose down -v`。

改密码：编辑 `.env`（已加入 `.gitignore`，不会提交）。

---

## 2. 桌面端：jpackage 双击包

前置：JDK 17+（含 `jpackage`）、Maven、可联网。

```powershell
.\scripts\build-desktop-installer.ps1
```

- 默认生成 **app-image**：`outputs\TerminalLedger\TerminalLedger.exe`，**双击即用**，无需 JDK。
- 想要 MSI 安装包（需本机装 WiX 3.x）：
  ```powershell
.\scripts\build-desktop-installer.ps1 -Version 1.2.0 -Msi
  ```

原理：`maven-shade-plugin` 把 H2/Jackson/OkHttp/JavaFX 打成单个 fat jar，再用 `jpackage` 生成原生启动器，携带运行库，用户无需自装环境。

> 提示：若个别机器上 JavaFX 从 fat jar 加载失败，可改用 jlink 生成 `--runtime-image` 再交由 `jpackage --runtime-image`，文档后续可补充该做法。

---

## 3. Android：release APK

前置：Android Studio（含 Gradle/SDK）、JDK。

第一步（只做一次）生成签名：
```powershell
.\scripts\generate-release-keystore.ps1
```
这会生成 `android\release.jks` 与 `android\keystore.properties`（均已加入 `.gitignore`，不会提交）。

第二步构建并校验：
```powershell
.\scripts\build-android-release.ps1
```

产物：`outputs\apk\TerminalLedger-v0.1.0.apk`（及同名 `.zip`）。

把它传到手机（QQ/网盘/数据线均可）→ 手机安装时允许“安装未知来源应用”。

### 安装后如何连到你的后端

1. 手机端“设置 → 云端地址”填电脑局域网 IP（例如 `http://192.168.1.100:8080`），点保存。
2. 回到 App 定位“立即同步”。地址会被持久化（`MainActivity` 启动时恢复），下次无需再设。
3. 确保后端已启动、手机与电脑同一 Wi-Fi、防火墙放行 8080。

> 构建签名时若想正式发布到应用市场，请替换为你自己的 keystore，并提高 `versionCode`、`versionName`。

---

## 4. 需要联网吗？

- **构建时**：Maven 下载依赖 + Gradle 下载依赖/Android Gradle Plugin + Docker 拉基础镜像，**需要联网**。
- **下载者使用桌面包**：`app-image` 自带运行库，无需联网。
- **手机端**：仅同步时需要连到你的后端；本地记账、报表完全离线。

---

## 5. 各端默认后端地址（可改）

| 端 | 默认 | 修改位置 |
| --- | --- | --- |
| 桌面端 | `http://localhost:8080` | `desktop/.../sync/SyncConfig.java` |
| 手机端 | `http://10.0.2.2:8080`（模拟器） | `android/.../sync/SyncConfig.java`（首次）或 App“设置”界面（运行时，首选） |
| 后端 | 容器内 `:8080` | `docker-compose.yml` 端口映射 |
