# 手機怎麼連到你的電腦

## 先讀這段，它決定了後面所有選擇

Android 9 以後預設**禁止明文 HTTP**。要放行，得把目的地寫進
`app/app/src/main/res/xml/network_security_config.xml`。這份檔案有兩個性質，
選連線方式之前必須知道：

1. **它是編譯期資源。** 執行期換不了，改了就要重編 APK。
2. **它不支援 CIDR。** 寫不了「整個 `100.64.0.0/10` 放行」，位址要逐一列出。

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

然後重編。另外 server 預設拒絕在非 tailnet 位址啟動——
`config.resolve_bind_host()` 會擋，那條是刻意的安全地基，要改請先讀懂它為什麼在那裡。

**正式 HTTPS**

整個 `domain-config` 區塊刪掉即可。`base-config` 本來就信任系統憑證，
走 https 不需要任何白名單。

## 手機深度省電會斷線

Android 的電池最佳化會殺掉背景連線。App 用的是 `specialUse` 類型的前景服務
（不是 `dataSync`——那個在 Android 15+ 一天只准跑 6 小時，而且沒實作
`onTimeout()` 會直接崩），但廠商自己的省電策略仍然管得到它。

斷線後 App 會用 `seq` 續傳，不會掉事件。真的要它一直活著，
在系統設定裡把這支 App 的電池最佳化關掉。
