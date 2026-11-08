# Dcard X Tweaks

適用於 **Dcard X**（`com.dcard.freedom`）的 LSPosed 模組：移除所有廣告，並自動展開被系統隱藏的留言。

## 功能

### 🚫 移除廣告
| 項目 | 做法 |
| --- | --- |
| 開屏廣告 | 停用開屏廣告判斷邏輯；若仍開啟 `OpenAdPlayerActivity` 則直接關閉 |
| 看板列表 / 文章內 / 留言區廣告 | 攔截 `DcardAdNetwork` 的廣告載入並回傳失敗結果，`AdItemLoader.load()` 一律不載入 |
| 殘留廣告版位 | `DcardAdViewContainer` 附加到畫面時直接收合，不留空白 |
| 品牌活動（Mercury）區塊 | 強制 Remote Config `disable_mercury_on_freedom = true` |

### 💬 還原系統隱藏的留言
Dcard 會把被「負面品質標記」的留言自動摺疊，只能滑到留言串最底部點「**系統已經隱藏部分留言，查看更多**」才看得到，巢狀回覆中也會顯示「**隱藏留言**」佔位。

本模組讓這些留言**直接顯示在原本的樓層位置**，不必再另外點進「隱藏的留言」頁面：

- 主留言串中被隱藏的留言照常顯示
- 巢狀回覆中的「隱藏留言」佔位改為顯示原留言
- 底部「系統已經隱藏部分留言，查看更多」提示不再出現

> 原理：API 回傳的留言帶有 `isThrottled` 旗標，App 端所有隱藏邏輯都經過 `Comment` 上同一個判斷方法。模組於執行期透過 `toString()` 反推出 `isThrottled` 欄位，再找出只依賴該欄位的判斷方法並強制回傳 `false`，因此不依賴混淆後的方法名稱，Dcard 改版後通常仍可運作。

## 需求

- Android 10（API 29）以上
- 已 Root，並安裝支援 **libxposed API 102** 的 LSPosed
- Dcard X（`com.dcard.freedom`），開發時測試版本 **8.241.2-freedom**

## 安裝

1. 從 [Releases](https://github.com/Jas0n1ee0315/Dcard-X-Tweaks/releases) 下載最新的 APK 並安裝
2. 在 LSPosed 中啟用 **Dcard X Tweaks**（作用域固定為 `com.dcard.freedom`）
3. 強制停止 Dcard X 後重新開啟

可用 `adb logcat -s DcardXTweaks` 或 LSPosed 日誌確認各 Hook 是否成功套用。

## 建置

```bash
./gradlew assembleDebug
```

輸出位於 `app/build/outputs/apk/debug/app-debug.apk`。

- Android Gradle Plugin 9.1（內建 Kotlin）
- [libxposed API](https://github.com/libxposed/api) 102（compileOnly）
- [DexKit](https://github.com/LuckyPray/DexKit) 2.0.7：用來在混淆後的程式碼中定位廣告相關類別，掃描結果會依 APK 指紋快取於 Dcard 的 cache 目錄

## 免責聲明

本專案僅供學習與研究用途，與 Dcard 官方無關。使用本模組所產生的任何後果由使用者自行承擔。
