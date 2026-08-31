# Audio Share

[English](README.md) | 简体中文

Audio Share通过网络将 Windows电脑的音频传输到 Android设备播放。

本仓库是 [mkckr0/audio-share](https://github.com/mkckr0/audio-share) 的维护分支，保留原作者署名及 Apache-2.0许可证。

[下载最新版本](https://github.com/lkamhk/audio-share/releases/latest) · [报告问题](https://github.com/lkamhk/audio-share/issues)

## 本分支的主要改动

- **多设备及多服务器支持：** Android客户端可以同时连接多台 Audio Share服务器，并分别设置名称、地址、端口、启用状态及音量。
- **自动发现服务器：** 自动搜索局域网内可用的 Audio Share服务器。应用首次启动时不再建立无效的默认服务器。
- **更可靠的音频传输：** Protocol v2加入数据包序号、帧索引、乱序重排、去重、有界抖动缓冲、单次重传及超时丢包补偿。
- **稳定的 Android播放：** 使用有序音频队列、启动预缓冲、固定渲染周期及完整阻塞写入，避免部分写入造成 PCM数据丢失。
- **更安全的多来源混音：** 使用浮点累加、自动预留余量，并修正 PCM8、PCM16、PCM24、PCM32及浮点格式转换，避免硬削波失真。
- **改善 Windows音频捕获：** WASAPI每次读取所有可用数据包，并正确处理静音数据包、音频帧对齐及数据中断。
- **带签名的自动更新：** Windows及 Android安装更新前，会使用 ECDSA P-256/SHA-256分离签名验证更新文件。
- **实时诊断数据：** Android显示队列延迟、丢包、迟到及乱序数据包、重传、丢包补偿、缓冲不足、重新缓冲及 AudioTrack underrun等信息。

## 许可证

本项目依据 [Apache License 2.0](LICENSE) 发布，并保留原作者版权声明：

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
