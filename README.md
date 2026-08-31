# Audio Share

Audio Share 將 Windows／Linux電腦嘅音訊透過網絡傳送到 Android裝置播放。

本專案係 [mkckr0/audio-share](https://github.com/mkckr0/audio-share) 嘅維護分支，保留原作者 attribution及 Apache-2.0授權。

[下載最新版本](https://github.com/lkamhk/audio-share/releases/latest) · [回報問題](https://github.com/lkamhk/audio-share/issues)

## 本分支主要改動

- **多設備／多 Server支援**：Android client可以同時連接多部 Audio Share Server，分別設定名稱、地址、連接埠、啟用狀態及音量，並混合到同一個播放輸出。
- **自動搜尋 Server**：自動發現區域網絡內可用嘅 Audio Share Server；首次啟動唔會預設建立無效 Server，由使用者搜尋或手動新增。
- **改善音訊穩定性**：Protocol v2加入 sequence、frame index、排序、去重、jitter buffer、限時重傳及丟包 concealment，減少爆音、噪點同短暫中斷。
- **改善 Android播放**：使用有界音訊佇列、啟動 prebuffer、固定 render週期及完整 blocking write，避免 partial write導致 PCM資料遺失。
- **改善多來源混音**：以 float accumulator混音、保留 headroom並修正 PCM8／16／24／32及 float處理，避免 hard clipping。
- **改善 Windows擷取**：WASAPI每次讀盡可用 packets，正確處理 silent packet、frame alignment及 data discontinuity。
- **簽署自動更新**：Windows及 Android透過 Supabase檢查版本、由 Dropbox下載更新，並使用 ECDSA P-256/SHA-256 detached signature驗證檔案。
- **更新狀態統計**：Android顯示 queue latency、loss、late、reorder、retry、concealment、underflow、rebuffer及 AudioTrack underrun等資料，方便判斷網絡或播放問題。

詳細版本內容請參閱 [v0.4.8 release notes](docs/release-notes-v0.4.8.md)。

## License

本專案依照 [Apache License 2.0](LICENSE) 發佈，並保留原作者版權聲明：

```text
Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
