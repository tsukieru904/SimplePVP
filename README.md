# SimplePvP

> A lightweight PvP-control plugin for Paper servers — toggle stranger PvP, run a whitelist mode, and protect new players with a configurable grace period. GUI editor included.

一款輕量的 Paper 伺服器 PvP 控制外掛：可切換「陌生人 PvP」開關、開啟白名單模式，並提供可設定的新手保護期，附帶 GUI 編輯面板。

---

## 目錄

- [功能特色](#功能特色)
- [需求環境](#需求環境)
- [安裝方式](#安裝方式)
- [建置方式](#建置方式)
- [指令一覽](#指令一覽)
- [權限節點](#權限節點)
- [設定檔](#設定檔)
- [資料儲存](#資料儲存)
- [開發架構](#開發架構)

## 功能特色

- **陌生人 PvP 開關**：玩家可自行決定是否開放給不在白名單中的其他玩家攻擊。
- **PvP 白名單模式**：只有被加入列表的玩家才能攻擊你，可透過指令或 GUI 管理名單。
- **GUI 編輯面板**：`/pvp editor` 開啟圖形化選單，一鍵切換設定、瀏覽/管理白名單（含翻頁）。
- **新手保護期**：新加入的玩家會有一段時間免於被攻擊，並可用 BossBar 顯示倒數，也支援手動解除（附二次確認機制，避免手滑）。
- **雙儲存後端**：支援 SQLite 或 YAML 兩種資料儲存格式，可於設定檔切換。
- **熱重載**：`/simplepvp reload` 可重新載入設定檔與訊息檔，不需重啟伺服器。
- **完整可自訂訊息**：所有玩家可見文字皆可在 `message.yml` 中修改，支援 `&` 色碼。

## 需求環境

- Java 21+
- [Paper](https://papermc.io/) 26.1.2 或相容版本（依 `plugin.yml` 的 `api-version`）
- Maven 3.6+（僅建置時需要）

## 安裝方式

1. 至 [Releases](../../releases) 頁面下載已建置好的 `simple-pvp-1.0.0.jar`，或自行[建置](#建置方式)。
2. 將 jar 檔放入伺服器的 `plugins/` 資料夾。
3. 啟動或重啟伺服器，外掛會自動產生 `config.yml` 與 `message.yml`。
4. 依需求調整設定檔後，使用 `/simplepvp reload` 套用變更。

## 建置方式

本專案使用 Maven，並透過 `maven-shade-plugin` 將 SQLite JDBC 驅動打包進最終 jar（僅保留 Linux x86_64 原生函式庫以縮小體積）。

```bash
mvn clean package
```

建置完成後，可直接部署的 jar 會位於 `target/simple-pvp-1.0.0.jar`。

## 指令一覽

| 指令 | 說明 | 權限 |
| --- | --- | --- |
| `/pvp on` \| `/pvp off` | 開啟／關閉陌生人 PvP | `simplepvp.use` |
| `/pvp editor`（別名 `/pvpeditor`） | 開啟 PvP 設定 GUI 面板 | `simplepvp.use` |
| `/pvp help` | 顯示指令說明 | `simplepvp.use` |
| `/pvp confirm` | 確認解除新手保護期（需先執行 `/pvpProtect`） | `simplepvp.use` |
| `/pvplist on` \| `/pvplist off` | 開啟／關閉 PvP 白名單模式 | `simplepvp.use` |
| `/pvplist add <玩家>` | 將玩家加入白名單 | `simplepvp.use` |
| `/pvplist remove <玩家>` | 將玩家從白名單移除 | `simplepvp.use` |
| `/pvplist list` | 查看自己的白名單 | `simplepvp.use` |
| `/pvpProtect` | 手動解除新手保護期（30 秒內需 `/pvp confirm` 確認） | `simplepvp.use` |
| `/simplepvp reload` | 重新載入設定檔與訊息檔 | `simplepvp.admin` |

> 開啟陌生人 PvP 與白名單模式互斥：開啟其中一項會自動關閉另一項。

## 權限節點

| 節點 | 預設 | 說明 |
| --- | --- | --- |
| `simplepvp.use` | `true`（所有人） | 使用一般 PvP / 白名單 / 保護相關指令 |
| `simplepvp.admin` | `op` | 使用 `/simplepvp reload` 等管理指令 |

## 設定檔

### `config.yml`

```yaml
settings:
  default-stranger-pvp: false   # 新玩家預設是否開放陌生人 PvP
  default-pvplist-mode: false   # 新玩家預設是否啟用白名單模式

storage:
  type: sqlite                  # sqlite 或 yaml

newbie-protection:
  enabled: true
  duration-seconds: 300
  first-join-only: true         # 是否僅在玩家第一次加入時給予保護

bossbar:
  enabled: true
  title: "&a新手保護期 &7| 剩餘 &f{time}"
  color: GREEN
  style: SOLID

gui:
  editor-title: "&aPvP 編輯面板"
  whitelist-title: "&aPvP 白名單 &7({page}/{pages})"
```

### `message.yml`

所有面向玩家的文字（含提示訊息、GUI 按鈕文字、說明選單）皆集中於此檔，可自由修改並支援 Minecraft `&` 色碼。修改後執行 `/simplepvp reload` 即可套用，無需重啟。

## 資料儲存

外掛支援兩種儲存後端，透過 `config.yml` 的 `storage.type` 切換：

- **`sqlite`**（預設）：資料儲存於外掛資料夾內的 SQLite 資料庫檔案，適合資料量較大或需要更佳效能的伺服器。
- **`yaml`**：資料儲存於 `data.yml`，格式較易於人工檢視與編輯。

儲存讀寫皆以鎖保護，避免非同步儲存與重載/關閉流程之間互相干擾造成資料損毀。

## 開發架構

```
src/main/java/me/tsukieru/simplepvp/
├── SimplePvpPlugin.java     # 主類別，負責生命週期與元件註冊
├── command/PvpCommand.java  # 指令與 Tab 補全處理
├── data/PvpDataStore.java   # SQLite / YAML 資料存取層
├── gui/PvpGui.java          # GUI 面板產生邏輯
├── gui/PvpGuiListener.java  # GUI 點擊事件監聽
├── listener/PvpListener.java # PvP 傷害判定監聽
└── message/MessageManager.java # 訊息載入與格式化
```

