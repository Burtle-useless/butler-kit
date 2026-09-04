# 手機怎麼連到你的電腦

## 先讀這段，它決定了後面所有選擇

Android 9 以後預設**禁止明文 HTTP**。要放行，得把目的地寫進
`app/app/src/main/res/xml/network_security_config.xml`。這份檔案有兩個性質，
選連線方式之前必須知道：

1. **它是編譯期資源。** 執行期換不了，改了就要重編 APK。
2. **它不支援 CIDR。** 寫不了「整個 `100.64.0.0/10` 放行」，位址要逐一列出。
3. **debug build 讀的是另一份**（`app/app/src/debug/res/xml/` 底下的同名檔），
   而且是**整份覆蓋**不是合併。main 那份加了什麼，這裡也要加一次。它比 main
   多的只有模擬器看出去的 `10.0.2.2`。

所以「換一種連線方式」在 Android 這端必然是重編一次 APK，做成執行期的抽象層
是白費力氣——多一層介面，換不到任何東西。

預設值繞開了這個問題：白名單只寫 `ts.net` 加 `includeSubdomains="true"`，
涵蓋所有人的 Tailscale tailnet。位址變、換電腦、重裝 Tailscale，都不必回來重編。

## 五種選法

| 方案 | 優 | 缺 |
|---|---|---|
| **Tailscale**（預設） | NAT 穿透免設定、WireGuard 加密、MagicDNS 名稱固定不必重編 | 兩端都要裝並登入同帳號；公司／校園網路可能擋；手機深度省電會斷線 |
| 區網明文 HTTP | 零安裝、延遲最低、五分鐘就能看到畫面亮起來 | 出門就失聯；同網段的人抓得到 token；換網段要重編 |
| Cloudflare Tunnel | 不用開 port、自動正式憑證、手機端零設定 | 流量整條經過第三方，而這條連線傳的是**一台電腦的完整控制權**；免費版網域會變 |
| 自簽 HTTPS + DDNS | 不依賴外部服務 | 憑證要 pin 進 App、到期就得重編；要開 port 把服務曝在公網，被掃到就是整台電腦。**不建議任何人走** |
| 自架 WireGuard | 加密同級且不靠第三方 | 金鑰、DDNS、開 port 全部自理，新手會卡死在第一步 |

## 怎麼改

**Tailscale（不用改）**

兩端裝好登入同帳號，電腦端開 MagicDNS，App 的 host 填
`<主機名>.tailXXXX.ts.net:47362`。

用 IP 也能連（`ts.net` 白名單不涵蓋純 IP，得另外加一筆），但位址會變，
變一次就要重編一次 APK。用名字。

**區網明文**

XML 的 `domain-config` 區塊裡加：

```xml
<domain includeSubdomains="false">192.168.1.100</domain>
```

然後重編。server 那邊設 `BUTLER_BIND=192.168.1.100`——預設會去找 tailnet 位址，
沒設就起不來。啟動時它會警告一句「同網段的裝置都連得到」，那句是對的，不是誤報。

**Cloudflare Tunnel（有自己的網域就走這條）**

Android 這端什麼都不用改，走 https 不碰白名單，**不必重編 APK**。

1. `cloudflared tunnel run <名字>`，`ingress` 指向 `http://127.0.0.1:47362`
2. server 設 `BUTLER_BIND=127.0.0.1`
3. App 的 host 填那個網域（443 可省略）

服務只綁本機，cloudflared 從 loopback 取件，連同一個區網的其他裝置都掃不到——
比綁 tailnet 位址更緊。但通道的入口在公開網際網路上，**一定要在通道那層加驗證**
（Cloudflare Access service token 之類），別只靠 device token。

加了 Access 之後，App 要帶著服務憑證才進得去。憑證由 Gradle 從
`app/local.properties` 讀進 `BuildConfig`，`net/AccessAuth.kt` 再把它加到每個
請求的標頭上（含 SSE 與健康檢查）：

```properties
cfAccessClientId=xxxxx.access
cfAccessClientSecret=yyyyy
```

**不要把憑證寫進原始碼。** 那個檔在 `.gitignore` 裡；留空的話一個標頭都不加，
走 Tailscale 或區網的人完全不受影響。帶著憑證去連區網位址也無害（伺服器不認得
就忽略），所以 AccessAuth 不按網址分流——分流要判斷「現在連的是哪一條」，
而那個判斷會在使用者手動改主機欄位時出錯。

**正式 HTTPS**

整個 `domain-config` 區塊刪掉即可。`base-config` 本來就信任系統憑證，
走 https 不需要任何白名單。

## server 綁哪裡

`config.resolve_bind_host()` 決定，優先序：

1. `BUTLER_DEV=1` → 強制 `127.0.0.1`（本機驗證用，手機連不到）
2. `BUTLER_BIND=<位址>` → 照它走，**不再回頭找 tailnet**
   （tunnel + Tailscale 並存時要的就是這個）
3. 都沒設 → 自動找 tailnet 位址，找不到就拒絕啟動

**`BUTLER_BIND` 不接受 `0.0.0.0` 這類萬用位址，設了直接拒絕啟動。** 引擎跑在
bypassPermissions 底下，打得到這個 port 就等於能對那台電腦下任意指令，
在公用 Wi-Fi 上等於把電腦交出去。要從外面連進來請走通道。

## 手機深度省電會斷線

Android 的電池最佳化會殺掉背景連線。App 用的是 `specialUse` 類型的前景服務
（不是 `dataSync`——那個在 Android 15+ 一天只准跑 6 小時，而且沒實作
`onTimeout()` 會直接崩），但廠商自己的省電策略仍然管得到它。

斷線後 App 會用 `seq` 續傳，不會掉事件。真的要它一直活著，
在系統設定裡把這支 App 的電池最佳化關掉。
