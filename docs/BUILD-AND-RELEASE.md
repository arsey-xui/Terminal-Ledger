# 构建与发布（桌面包 / APK / Docker）

> 本仓库在无 Maven/Gradle/Android SDK 的环境中生成。请在任何**装有 JDK 17 + Maven**（桌面板、后端）或 **Android Studio**（手机端）并**可联网**的机器上执行即可产出最终产物。

## 0. 三个产物一览

| 产物 | 构建设置 | 一键命令 | 产物位置 |
| --- | --- | --- | --- |
| 后端容器 | `docker-compose.yml` + `backend/Dockerfile` | `docker compose up -d` | 运行中的服务 |
| 桌面包 | `desktop/pom.xml`(shade) + `scripts/build-desktop-installer.ps1` | `.\scripts\build-desktop-installer.ps1` | `desktop\target\dist\PersonalAccounting` |
| Android APK | `android/app/build.gradle`(签名) + `scripts/build-android-release.ps1` | `.\scripts\build-android-release.ps1 -GenerateKeystore` | `android\app\build\outputs\apk\release\app-release.apk` |

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

- 默认生成 **app-image**：`desktop\target\dist\PersonalAccounting\PersonalAccounting.exe`，**双击即用**，无需 JDK。
- 想要 MSI 安装包（需本机装 WiX 3.x）：
  ```powershell
  .\scripts\build-desktop-installer.ps1 -Version 0.1.0 -Msi
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

产物：`android\app\build\outputs\apk\release\app-release.apk`。

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
