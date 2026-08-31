# Audio Share 自動更新

Audio Share v0.4.8 使用同一個 Supabase Edge Function提供兩個獨立 channel：

- Windows：`audio-share-server-stable / windows / x86_64`
- Android：`audio-share-android-stable / android / universal`

更新檔案由 Dropbox傳送。Windows ZIP及 Android APK均使用同一組 ECDSA P-256/SHA-256 updater key產生 detached signature；client下載後必須先驗證簽章。

Android v0.4.7可以透過 Supabase updater升級至 v0.4.8。Windows v0.4.7雖然包含 updater，但版本比較器無法解析 manifest使用的純 SemVer格式，因此需要手動安裝一次 v0.4.8作為 bridge release；第一個完整 Windows自動更新測試應使用 v0.4.8 → v0.4.9。

## Android更新流程

1. WorkManager向固定 Supabase endpoint查詢 manifest。
2. 有更新時顯示通知。
3. 使用者按通知後，App從 Dropbox下載 APK。
4. App驗證 64-byte P1363 detached signature。
5. 驗證成功後顯示「可以安裝」通知。
6. 使用者按通知並在 Android系統安裝畫面確認。

一般 Android app不能靜默安裝更新。第一次使用這個功能時，系統可能要求允許 Audio Share「安裝未知應用程式」。不需要 USB debugging。

更新 APK必須沿用舊版相同的 Android signing keystore，否則 Android會拒絕覆蓋安裝。

## Supabase Edge Function

支援 Windows及 Android的 source位於：

```text
updater/supabase/functions/audio-share-update/index.ts
```

`audio-share-update`是 Audio Share專用 function。現有 `launcher-update`屬於 Saladict，禁止覆蓋、刪除或重新命名。

Audio Share亦使用獨立的 `public.audio_share_updates` table，不會讀寫 Saladict的 `public.launcher_updates`。先在 Supabase Dashboard的 SQL Editor執行：

```text
updater/supabase/audio-share-updates.sql
```

腳本只會建立 Audio Share專用 table、啟用 RLS並授權 `service_role`；不會修改現有 table或 release rows。

部署前，先將整個 function folder複製到目前共用 Supabase repository的新目錄：

```text
supabase/functions/audio-share-update/index.ts
```

Windows PowerShell範例：

```powershell
$audioShareRepo = Resolve-Path "."
$supabaseRepo = Resolve-Path "..\your-supabase-project"
$source = Join-Path $audioShareRepo "updater\supabase\functions\audio-share-update"
$destination = Join-Path $supabaseRepo "supabase\functions\audio-share-update"
Copy-Item -LiteralPath $source -Destination $destination -Recurse
Set-Location $supabaseRepo
```

如果 destination已存在，代表專用 function source以前已經複製；先比較內容，不要使用會影響其他目錄的遞迴刪除命令。

首次使用 CLI時登入，然後只部署指定 function：

```powershell
npx supabase login
npx supabase functions deploy audio-share-update `
  --project-ref nfqkislweudltvckonog `
  --no-verify-jwt `
  --use-api
```

禁止省略 function名稱，亦禁止加入 `--prune`。`--use-api`表示毋須啟動 Docker；`--no-verify-jwt`讓沒有 Supabase帳戶的 Audio Share client可以公開檢查版本。function只提供只讀更新 metadata，資料庫讀取使用 Supabase自動提供的 server-side secret。

部署後 endpoint是：

```text
https://nfqkislweudltvckonog.supabase.co/functions/v1/audio-share-update
```

未發佈 update row時，以下 read-only檢查應同時得到 HTTP 204：

```powershell
.\updater\test-update-manifest.ps1 -CurrentVersion 0.4.7
```

必須在正式發佈 row之前完成部署；build script不會自動部署 Supabase。

## 正式 build

只驗證環境及版本：

```powershell
pwsh -File .\build-release.ps1 -ValidateOnly
```

正式 build、測試及簽章：

```powershell
pwsh -File .\build-release.ps1
```

同時複製更新檔案到本機 Dropbox sync folder：

```powershell
pwsh -File .\build-release.ps1 -StageDropbox
```

輸出位於 `release/AudioShare-v<version>/`，包括：

- `AudioShare-Android-v<version>.apk`
- `AudioShare-Android-v<version>.apk.sig`
- `AudioShare-Server-Update-Windows-x64-v<version>.zip`
- `AudioShare-Server-Update-Windows-x64-v<version>.zip.sig`
- 手動安裝包及 `SHA256SUMS.txt`

build只要求輸入一次 updater private-key password，並分別簽署 APK及 Windows ZIP。build不會修改 Supabase。

## 發佈到 Dropbox及 Supabase

Dropbox完成同步後，為 APK及 Windows update ZIP分別建立公開 shared link。`.sig`不用公開 URL，publish script會把簽章內容寫入 Supabase row。

先以 disabled狀態寫入 Windows row：

```powershell
$env:AUDIO_SHARE_SUPABASE_SECRET_KEY = "<temporary-secret>"
.\updater\publish-supabase-update.ps1 `
  -Target windows `
  -ZipUrl "https://www.dropbox.com/scl/fi/.../AudioShare-Server-Update-Windows-x64-v0.4.6.zip?dl=0" `
  -SignaturePath ".\release\AudioShare-v0.4.6\AudioShare-Server-Update-Windows-x64-v0.4.6.zip.sig" `
  -Version "0.4.6"
```

寫入 Android row：

```powershell
.\updater\publish-supabase-update.ps1 `
  -Target android `
  -ZipUrl "https://www.dropbox.com/scl/fi/.../AudioShare-Android-v0.4.7.apk?dl=0" `
  -SignaturePath ".\release\AudioShare-v0.4.7\AudioShare-Android-v0.4.7.apk.sig" `
  -Version "0.4.7"
```

確認資料正確後，用相同 command加入 `-Enable`。完成後移除目前 shell內的 secret：

```powershell
Remove-Item Env:AUDIO_SHARE_SUPABASE_SECRET_KEY
```

## 不使用 ADB的 Android實機測試

1. build v0.4.6 bridge release，並以一般檔案管理器手動安裝 APK。
2. 保留相同 source及 signing keystore，將版本升至 v0.4.7後再 build。
3. 等待 v0.4.7 APK完成 Dropbox同步。
4. 部署新版 Edge Function。
5. 將 Android v0.4.7 row設為 enabled。
6. 在手機 v0.4.6 Audio Share開啟通知權限。
7. 前往 Settings，按 `Check for update`。
8. 按「有新版本」通知開始下載。
9. 等待「可以安裝」通知，再按通知。
10. 如系統要求，允許 Audio Share安裝未知應用程式。
11. 在 Android系統畫面確認更新，重開 App後核對標題為 v0.4.7，並確認 server清單及其他設定仍然保留。

發佈後可在電腦做 read-only manifest檢查：

```powershell
.\updater\test-update-manifest.ps1 -Target android -CurrentVersion 0.4.6 -RequireUpdate
```

## Rollback

- 緊急停止發佈：將相應 channel row的 `enabled`設為 `false`。
- Windows安裝失敗會使用 `.bak`復原舊 exe。
- Android安裝由系統處理；同一 package只能安裝相同簽署憑證且 versionCode更高的 APK。
- Android不支援以自動更新流程直接降版；需要另行手動處理。
