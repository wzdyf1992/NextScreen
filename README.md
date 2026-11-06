# NextScreen

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-3DDC84.svg)](https://developer.android.com)

**NextScreen** 是一款面向 Android 平板与大屏设备打造的 **DLNA/UPnP 媒体渲染器（DMR）+ AirPlay 屏幕镜像与无线副屏接收端**。将它安装在 Android 平板上，同一局域网（或平板热点）下的手机、iPad 与 Mac 即可直接发现并投屏——兼具视频连播倍速投屏与低延迟苹果屏幕镜像/扩展副屏能力。

> An Android **DLNA/UPnP Media Renderer (DMR)** + **AirPlay Screen Mirroring & Wireless Extended Display Receiver** built with Kotlin, Media3 ExoPlayer, OpenGL ES 2.0, Oboe, and UxPlay.

---

## 下载与安装

前往 [**GitHub Releases**](https://github.com/wzdyf1992/NextScreen/releases) 下载最新版本的安装包（`NextScreen-v1.1.0.apk`），直接安装即可使用。

- **系统要求**：Android 7.0 及以上（API 24+），支持 `arm64-v8a`、`armeabi-v7a`、`x86_64` 架构。
- **推荐权限设置**（可在应用内「设置 → 权限与后台」一键跳转开启）：
  - **显示在其他应用上层（悬浮窗权限）**：允许平板处于后台或锁屏状态时，收到投屏/镜像请求自动弹出播放画面（小米/红米平板建议同时开启「后台弹出界面」）。
  - **忽略电池优化**：保证后台投屏发现服务长期稳定在线。

---

## 核心特性

### 1. AirPlay 屏幕镜像 & Mac 无线扩展副屏
- 通过 Android `NsdManager` 广播标准 `_airplay._tcp` 与 `_raop._tcp` mDNS 服务，原生免装客户端即连。
- **Mac 投屏与扩展显示器**：支持 Intel 与 Apple Silicon（M1 / M2 / M3 / M4）Mac 通过「控制中心 → 屏幕镜像」连接，同时支持**屏幕镜像**与**用作单独显示器（无线扩展副屏）**。
- **iPhone / iPad 投屏**：支持 iOS / iPadOS「控制中心 → 屏幕镜像」，以及 AirPlay 视频投送（`/play` HLS）和 RAOP 实时音频流（`AAC-ELD`、`AAC-LC`、`ALAC`、`PCM`）。
- **低延迟音视频管线**：
  - 视频采用 `AMediaCodec` 硬件加速解码 H.264 / HEVC（H.265），直通持久化 OpenGL ES 2.0 `SurfaceTexture` 渲染管线，支持最高 2K（2560×1440）/ 60fps、画面比例即时切换（适应 / 铺满 / 裁剪）与旋转控制。
  - 音频采用 `AMediaCodec` + 自适应时间轴抖动缓冲 + Google `Oboe`（`AAudio` / `OpenSL ES`）低延迟输出。
  - 支持可选的 **4 位 PIN 配对码**安全验证。

### 2. DLNA / UPnP 视频投屏渲染器
- 完整实现 SSDP 设备发现、`AVTransport` / `RenderingControl` / `ConnectionManager` SOAP 控制服务及 GENA 状态事件订阅。
- **自动连播下一集**：支持 `SetNextAVTransportURI` 预加载与单集结束 `STOPPED` 状态上报，配合 B站、优酷、爱奇艺、腾讯视频等主流视频 App 实现剧集无缝自动连播。
- **倍速记忆与画质切换**：
  - 支持 `0.5x ~ 3.0x` 播放倍速调节，切集或新投屏自动保持上次倍速；支持**长按屏幕临时加速**（如 2.0x / 3.0x）。
  - 支持 HLS / DASH 多码率轨道选择（最高画质 / 4K / 2K / 1080P / 720P / 480P / 自动）。

### 3. 平板交互体验
- 播放器支持左侧上下滑动调节亮度、右侧上下滑动调节音量、左右滑动快进快退。
- 支持自定义设备广播名称、投屏历史记录回放与开机自动启动接收服务。

---

## 快速使用指南

1. 确保手机 / iPad / Mac 与 Android 平板连接在**同一个 Wi-Fi 局域网**（或直接连接平板开启的热点）。
2. 打开平板上的 **NextScreen**，确认顶部状态指示为在线。
3. **发起连接**：
   - **视频 App 投屏**：在手机视频 App 播放页点击 `TV / 投屏` 图标，选择 `NextScreen-<设备型号>`。
   - **iPhone / iPad 镜像**：打开系统「控制中心 → 屏幕镜像」，选择本机名称。
   - **Mac 镜像或无线副屏**：点击菜单栏「控制中心 → 屏幕镜像」选择本机，连接后可在菜单中自由切换「镜像内建显示器」或「用作单独显示器」。

---

## 本地编译构建

### 环境要求
- **JDK**：17
- **Android SDK**：`compileSdk = 36`，`minSdk = 24`
- **Android NDK**：`28.2.13676358`
- **CMake**：`3.22.1`

### 构建命令

克隆仓库后，确保已配置 `ANDROID_HOME`（或在项目根目录创建 `local.properties` 写入 `sdk.dir=/path/to/android/sdk`），执行：

```bash
# Linux / macOS
./gradlew assembleRelease

# Windows PowerShell
.\gradlew.bat assembleRelease
```

编译产物位于：`app/build/outputs/apk/release/app-release.apk`。

---

## 项目结构

```text
NextScreen/
├── app/src/main/
│   ├── cpp/                         # Native AirPlay 接收核心与低延迟音频引擎
│   │   ├── native_bridge.cpp        # Kotlin <-> Native JNI 桥接层
│   │   ├── android_raop_callbacks.c # RAOP 视频/音频/控制回调事件转发
│   │   ├── android_dnssd_shim.c     # 对接 Android NsdManager 的 mDNS TXT 记录适配层
│   │   ├── audio_engine.cpp         # AMediaCodec 解码 + Timeline 缓冲 + Oboe 低延迟音频输出
│   │   └── third_party/             # UxPlay (AirPlay/RAOP 核心) 与 libplist 源码
│   ├── java/com/nextscreen/app/
│   │   ├── airplay/                 # AirPlay 服务管理、NSD 广播、硬解选型与 OpenGL ES 渲染管线
│   │   ├── dlna/                    # 内置 HTTP 服务、SSDP 组播、SOAP 动作解析、GENA 事件与 UPnP 描述符
│   │   ├── player/                  # 基于 Media3 ExoPlayer 的投屏播放器与状态机
│   │   ├── service/                 # 前台常驻服务、网络状态监听、通知栏与开机广播接收器
│   │   ├── data/                    # 本地投屏历史记录存储
│   │   └── ui/                      # 主界面、视频投屏页、AirPlay 镜像页与设置页
│   └── res/                         # 界面布局、图标与配置项资源
├── build.gradle.kts
├── settings.gradle.kts
└── LICENSE
```

---

## 开源协议与致谢

NextScreen 的原生 AirPlay 接收模块基于以下优秀的开源项目构建：

- [UxPlay](https://github.com/FDH2/UxPlay) — GPL-3.0
- [libplist](https://github.com/libimobiledevice/libplist) — LGPL-2.1+
- [llhttp](https://github.com/nodejs/llhttp) — MIT
- [AndroidX Media3 (ExoPlayer)](https://github.com/androidx/media) & [Google Oboe](https://github.com/google/oboe) — Apache-2.0

根据 UxPlay 的传染性开源协议要求，本项目整体采用 [**GNU General Public License v3.0 (GPL-3.0)**](LICENSE) 协议开源发布。
