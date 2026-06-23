# DUIX Mobile — 整体代码架构设计与软件介绍文档

> 版本：4.4.77（versionCode 77）
> 更新日期：2026-06-22
> 仓库：[LancerXiao/Duix-Mobile-SDK](https://github.com/LancerXiao/Duix-Mobile-SDK)
> 下载：[https://www.enlyai.com/downloads/duix/](https://www.enlyai.com/downloads/duix/)

---

## 目录

1. [项目概述](#1-项目概述)
2. [整体架构总览](#2-整体架构总览)
3. [分层架构详解](#3-分层架构详解)
4. [核心数据流与管线](#4-核心数据流与管线)
5. [DUIX SDK 架构（渲染核心）](#5-duix-sdk-架构渲染核心)
6. [应用层架构（test 模块）](#6-应用层架构test-模块)
7. [服务层架构](#7-服务层架构)
8. [UI 层架构](#8-ui-层架构)
9. [状态机与流式 TTS 协调器](#9-状态机与流式-tts-协调器)
10. [CI/CD 与部署架构](#10-cicd-与部署架构)
11. [跨平台架构（iOS 端）](#11-跨平台架构ios-端)
12. [关键设计决策与亮点](#12-关键设计决策与亮点)
13. [附录：关键文件清单](#13-附录关键文件清单)

---

## 1. 项目概述

### 1.1 项目定位

DUIX Mobile 是一款**端侧实时交互式 AI 数字人**解决方案，由 [duix.com](http://www.duix.com) 开源。核心能力是在移动设备（Android/iOS/平板/车机/VR/IoT）上本地运行数字人渲染引擎，实现：

- **实时唇形同步**：语音驱动数字人口型，延迟 < 120ms（骁龙 8 Gen 2 实测）
- **流式音频支持**：边合成边播放，支持打断和插话
- **端侧部署**：核心渲染不依赖云端，弱网环境下仍可运行
- **模块化可定制**：LLM / ASR / TTS 引擎可自由替换

### 1.2 应用场景

- 智能客服与虚拟助手
- 虚拟医生 / 律师 / 导师
- 虚拟伴侣与情感陪伴
- 车载语音助手、大屏交互终端

### 1.3 技术栈概览

| 层级 | 技术选型 |
|------|----------|
| 应用层 | Kotlin + AndroidX + OkHttp + Glide |
| 渲染 SDK | Java + JNI + C++ + OpenGL ES 2.0 |
| 神经网络推理 | ncnn（腾讯）+ ONNX Runtime |
| 图像处理 | OpenCV 4.6.0 |
| 音频处理 | AudioTrack + MediaCodec + 自实现 MFCC/FFT |
| LLM | Agnes AI / MiMo（流式 SSE） |
| ASR | DashScope WebSocket（fun-asr-realtime） |
| TTS | Qwen3-TTS / MiMo TTS / Edge TTS / Android TTS |
| CI/CD | GitHub Actions + 阿里云 ECS + Nginx |

---

## 2. 整体架构总览

DUIX Mobile 采用**五层架构**，从下到上依次为：

```
┌─────────────────────────────────────────────────────────────┐
│  ⑤ CI/CD 与部署层                                            │
│     GitHub Actions → 阿里云 ECS → Nginx 静态服务             │
├─────────────────────────────────────────────────────────────┤
│  ④ 应用层（test 模块）                                       │
│     CallActivity 状态机 + ASR/LLM/TTS 服务编排              │
│     + UI 组件 + 健康监控 + 自测引擎                          │
├─────────────────────────────────────────────────────────────┤
│  ③ 服务层                                                    │
│     LlmService / HybridAsrService / 4 个 TTS 引擎           │
│     + PcmResampler + Mp3ToPcmConverter + 下载管理            │
├─────────────────────────────────────────────────────────────┤
│  ② DUIX SDK（duix-sdk 模块）                                │
│     Java API（DUIX.java）+ RenderThread + AudioPlayer        │
│     + OpenGL ES 渲染层（DUIXTextureView/DUIXRenderer）       │
├─────────────────────────────────────────────────────────────┤
│  ① Native 引擎层（C++，编译为 libgjduix.so）                 │
│     duix（编排）+ dhmfcc（音频特征）+ dhunet（图像合成）      │
│     + dhcore（基础设施）+ aes（加密）                        │
└─────────────────────────────────────────────────────────────┘
```

### 模块依赖关系

```
test 模块（应用）
  ├── duix-sdk（本地模块，数字人渲染核心）
  │     └── libgjduix.so（C++ native 引擎）
  ├── AndroidX / OkHttp / Glide（第三方库）
  └── LLM / ASR / TTS 云服务 API
```

---

## 3. 分层架构详解

### 3.1 Native 引擎层（C++）

**构建产物**：`libgjduix.so`（通过 [CMakeLists.txt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/cpp/CMakeLists.txt) 编译）

**四大 C++ 模块**：

| 模块 | 路径 | 职责 |
|------|------|------|
| `dhcore` | `cpp/dhcore/` | 基础设施：内存管理（`jbuf_t`/`jmat_t`）、线程安全队列（`jqueue_t`）、无锁队列（moodycamel） |
| `dhmfcc` | `cpp/dhmfcc/` | 音频特征提取：PCM → MFCC → BNF（Bottleneck Feature），基于 WeNet ONNX 编码器 |
| `dhunet` | `cpp/dhunet/` | 图像口型合成：BNF 特征 + 静默帧 → UNet 神经网络 → 带口型视频帧，基于 ncnn |
| `duix` | `cpp/duix/` | 顶层编排：整合 dhmfcc + dhunet，提供 `dhduix_t` 引擎统一接口 |
| `aes` | `cpp/aes/` | 模型文件加密/解密（AES-CBC + Base64） |

**核心数据结构**：

- `dhduix_t`：引擎单例，持有 WeNet 编码器、UNet 模型、PCM 会话队列、后台计算线程
- `PcmSession`：流式 PCM 会话，管理音频分块、BNF 计算、帧索引读取
- `MWorkMat`：口型合成工作矩阵，负责 ROI 提取、UNet 推理、alpha 融合

### 3.2 DUIX SDK 层（Java）

**入口类**：[DUIX.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/DUIX.java)

**核心组件**：

| 组件 | 职责 |
|------|------|
| `DUIX` | 面向应用的唯一 API 入口，线程安全转发到 RenderThread |
| `RenderThread` | 带 Looper 的子线程，管理渲染循环和音频推送会话 |
| `AudioPlayer` | 基于 AudioTrack 的 PCM 播放器，16kHz/mono/16bit |
| `DUIXTextureView` | OpenGL ES 2.0 渲染容器（基于 TextureView） |
| `DUIXRenderer` | 渲染管道连接点，实现 Renderer + RenderSink 双接口 |
| `ImageDrawer` | GLSL 着色器绘制器，处理 BGR 纹理 + mask alpha 混合 |
| `DuixNcnn` | JNI 桥接类，声明所有 native 方法 |

**核心 API**：

```java
DUIX duix = new DUIX(context, modelUrl, renderSink, callback);
duix.init();                    // 初始化模型
duix.startPush();               // 开始音频推送会话
duix.pushPcm(byte[] buffer);   // 推送 PCM 数据（16kHz）
duix.stopPush();                // 结束会话
duix.startMotion(name, now);    // 播放动作
duix.release();                 // 释放资源
```

### 3.3 服务层

详见 [第 7 节](#7-服务层架构)。

### 3.4 应用层

详见 [第 6 节](#6-应用层架构test-模块)。

### 3.5 CI/CD 层

详见 [第 10 节](#10-cicd-与部署架构)。

---

## 4. 核心数据流与管线

### 4.1 完整对话数据流

```
用户按下麦克风
    │
    ▼
[HybridAsrService] 录音 PCM 16kHz
    │
    ├──→ DashScope WebSocket ASR（主）
    │      └── onPartialResult → 实时用户字幕（showUserBubbleStreaming）
    │      └── onFinalResult → 识别文本
    │
    ▼（fallback）
[AndroidAsrService] 原生 Google 语音识别
    │
    ▼
[LlmService] 流式 SSE 请求
    │
    ├── onToken → 累积响应文本 + 实时 AI 字幕（showAiBubbleStreaming）
    │
    │   ┌─ 检测句末标点（。！？!?\n）或超 40 字符 ─┐
    │   │                                           │
    │   ▼                                           │
    │ [流式 TTS 协调器] onStreamingTtsSentenceStart │
    │   │                                           │
    │   ▼                                           │
    │ [TTS 引擎分发] streamSynthesize(sentence)     │
    │   │                                           │
    │   ├── Qwen TTS（WebSocket，24kHz PCM）         │
    │   ├── MiMo TTS（REST，PCM）                    │
    │   ├── Edge TTS（WebSocket，MP3）               │
    │   └── Android TTS（TextToSpeech）              │
    │      │                                        │
    │      ▼                                        │
    │ [PcmResampler] 24kHz → 16kHz 重采样            │
    │      │                                        │
    │      ▼                                        │
    │ [DUIX SDK] startPush() → pushPcm()            │
    │      │                                        │
    │      ├──→ AudioPlayer 播放（用户听到声音）      │
    │      │                                        │
    │      └──→ Native 引擎：                        │
    │            PCM → MFCC → BNF → UNet → 口型帧   │
    │            → OpenGL ES 渲染数字人              │
    │                                              │
    │ [流式 TTS 协调器] onStreamingTtsSentenceDone ─┘
    │
    ▼ onComplete
[onStreamingLlmComplete] → 所有 TTS 完成 → stopPush()
    │
    ▼
AUDIO_PLAY_END 回调 → setState(IDLE) → auto-listen（循环）
```

### 4.2 数字人渲染流水线（Native 层）

```
应用层: DUIX.startPush()
    → RenderThread.handleStartPushAudio()
    → DuixNcnn.newsession() [native: dhduix_newsession → PcmSession]
    → AudioPlayer.pushStart()

应用层: DUIX.pushPcm(byte[])
    → DuixNcnn.pushpcm(sessid, data)
      [native: dhduix_pushpcm → PcmSession.pushpcm]
        → 后台 calcworker 线程:
            PcmSession.runcalc → WeAI.run(ONNX) → 生成 BNF 特征
    → AudioPlayer.pushData(data) [AudioTrack 播放]

渲染循环 (RenderThread.renderStep, 每 40ms):
    → AudioPlayer.getPlayIndex() 获取当前播放帧索引
    → DuixNcnn.readycnt() 检查 BNF 是否就绪
    → DuixNcnn.filerst(sessid, picPath, maskPath, rect, bnfIndex, rawBuf, maskBuf)
      [native: dhduix_fileinx → dhduix_simpinx
        → PcmSession.readblock 读取 BNF
        → dhduix_simprst
          → MWorkMat.premunet 提取口型 ROI
          → Mobunet.domodel(ncnn 神经网络推理)
          → MWorkMat.finmunet 贴回前景]
    → RenderSink.onVideoFrame(ImageFrame)
    → DUIXRenderer.onDrawFrame → ImageDrawer.draw (OpenGL ES)

应用层: DUIX.stopPush()
    → DuixNcnn.finsession(sessid)
    → AudioPlayer.pushDone() [哨兵帧 → PlaybackThread 回调 onPlayEnd]
```

**关键同步机制**：`AudioPlayer.getPlayIndex()` 返回的播放帧索引直接传给 native `filerst(bnfIndex)`，实现"音频播放进度"与"口型帧索引"的精确对齐——这是数字人口型与声音同步的核心。

---

## 5. DUIX SDK 架构（渲染核心）

### 5.1 Java 公共 API

**入口**：[DUIX.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/DUIX.java)

构造函数：`DUIX(Context, modelUrl, RenderSink, Callback)`

| 方法 | 职责 |
|------|------|
| `init()` | 校验模型目录，创建并启动 RenderThread |
| `isReady()` | 模型初始化是否完成 |
| `startPush()` | 开始音频推送会话 |
| `pushPcm(byte[])` | 推送一帧 PCM（16k/mono/16bit） |
| `stopPush()` | 结束会话 |
| `playAudio(String wavPath)` | 便捷方法：读取 wav 并播放 |
| `startMotion(name, now)` | 播放动作区间 |
| `setVolume(float)` | 设置音量 |
| `release()` | 释放资源 |

**回调接口** `Callback.onEvent(event, msg, info)`，事件常量定义在 [Constant.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/Constant.java)：
- `init.ready` / `init.error`
- `play.start` / `play.end` / `play.error`
- `motion.start` / `motion.end`

### 5.2 渲染层

**渲染容器**：[DUIXTextureView](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/render/DUIXTextureView.java) — 继承 TextureView 的 OpenGL ES 2.0 容器，包含：
- `GLThread`：独立渲染线程，管理 EGL 上下文
- `EglHelper`：EGL display/context/surface 管理
- 支持按需渲染（`RENDERMODE_WHEN_DIRTY`）和连续渲染

**渲染管道**：[DUIXRenderer](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/render/DUIXRenderer.java) 同时实现 `Renderer` 和 `RenderSink`：
- `onVideoFrame(ImageFrame)`：接收 native 渲染帧，触发重绘
- `onDrawFrame`：清屏 → 启用混合（`glBlendFuncSeparate`）→ `ImageDrawer.draw()`
- 支持裁剪填充（`SCALE_TYPE_CROP`）和完整显示（`SCALE_TYPE_INSIDE`）

**着色器**：[ImageDrawer](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/render/ImageDrawer.java) 的片元着色器采样两张纹理（原图 + mask），用 mask RGB 均值作为 alpha，输出 `vec4(B, G, R, alpha)`（BGR 顺序，因 native 层输出 BGR）。

### 5.3 AudioPlayer

**路径**：[AudioPlayer.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/audio/AudioPlayer.java)

基于 Android `AudioTrack`，配置：
- 采样率 16000Hz / 单声道 / 16bit PCM
- 缓冲区 1280 字节（10ms 音频）

**关键设计**：
- `pushStart()`：清空队列，重启 AudioTrack 和 PlaybackThread（修复 stopPush 后无法恢复的 bug）
- `pushData(ByteBuffer)`：按 1280 字节切块入队
- `pushDone()`：放入哨兵帧（`completeEmptyFrame=true`），PlaybackThread 遇到则回调 `onPlayEnd()`
- `getPlayIndex()`：返回当前播放位置（40ms 一帧计数），**直接驱动 native 口型帧索引**

### 5.4 JNI 桥接

**路径**：[DuixJni.cpp](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/cpp/android/DuixJni.cpp)

将 [DuixNcnn.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/DuixNcnn.java) 的 native 方法映射到 C 函数 `dhduix_*`。使用 `GetPrimitiveArrayCritical` 零拷贝获取 PCM 字节。

### 5.5 C++ 核心模块

#### dhcore（基础设施）

**路径**：`cpp/dhcore/`

- `jbuf_t`：通用字节缓冲区，支持引用计数和链表
- `jmat_t`：矩阵结构，封装 OpenCV `cv::Mat` 和 ncnn `ncnn::Mat` 互转
- `jqueue_t`：线程安全队列，三种模式（SIMP/MUTI/LOCK）
- 集成 moodycamel 无锁队列（ConcurrentQueue/ReaderWriterQueue）

#### dhmfcc（音频特征提取）

**路径**：`cpp/dhmfcc/`

将 PCM 转换为 BNF（Bottleneck Feature）语音特征向量：

- `PcmSession`：流式 PCM 会话管理，`pushpcm` 接收数据，`runcalc` 后台计算 MFCC→BNF
- `WeAI`：WeNet 编码器抽象基类，输入 `[1,321,80]`（mel），输出 `[1,78,256]`（bnf）
  - `WeOnnx`：基于 ONNX Runtime（默认）
  - `WeOpvn`：基于 OpenVINO（条件编译）
- `mfcc/`：FFT、MFCC 算法、IIR 滤波器

**流式参数**（`aicommon.h`）：
- 块大小 20-50 帧，BNF 维度 256，总 BNF 20480
- MFCC 帧率 25fps，采样率 16000Hz

#### dhunet（图像口型合成）

**路径**：`cpp/dhunet/`

基于 ncnn 神经网络 + OpenCV，将 BNF 特征 + 静默帧合成为带口型视频帧：

- `Mobunet`：UNet 模型封装，`domodel(pic, msk, feat, rect=160)` 执行推理
- `MWorkMat`：口型合成工作矩阵，流程 `premunet` → `munet` → `finmunet` → alpha 融合
- `JMat`：核心矩阵类，支持 `loadjpg`/`savegpg`/`loadgpg`（加密图片格式）
- `blendgram.h`：26 种图像混合模式（Normal/Overlay/SoftLight/ColorDodge 等）

#### duix（顶层编排）

**路径**：`cpp/duix/gjsimp.cpp`

`dhduix_s` 结构体持有 WeNet 编码器（首帧 + 通用）、PcmSession、UNet 模型、图片缓冲，后台 `calcworker` 线程持续计算 BNF。

核心渲染流程 `dhduix_simprst`：
1. 用 BNF 构造 `JMat feat`
2. `MWorkMat wmat(mat_pic, NULL, box, kind)` 准备口型 ROI
3. `wmat.premunet()` → `wmat.munet()` 提取区域
4. `munet->domodel(mpic, mmsk, feat, rect)` 神经网络推理
5. `wmat.finmunet(mat_fg)` 贴回前景

---

## 6. 应用层架构（test 模块）

### 6.1 模块概览

**包名**：`ai.guiji.duix.test`
**compileSdk**：35 / **minSdk**：24 / **targetSdk**：34

**目录结构**：

```
test/src/main/java/ai/guiji/duix/test/
├── App.java                    # Application 入口（全局 Context + 崩溃捕获）
├── ui/
│   ├── activity/
│   │   ├── BaseActivity.java   # 抽象基类（HandlerThread + 权限封装）
│   │   ├── MainActivity.kt     # 首页（模型选择/下载/版本检查）
│   │   └── CallActivity.kt     # 对话页（核心，~3430 行）
│   ├── adapter/
│   │   ├── MessageAdapter.kt   # 消息列表适配器
│   │   ├── ModelSelectorAdapter.kt
│   │   └── MotionAdapter.kt
│   ├── dialog/                 # 4 个对话框
│   └── MessageData.kt          # 消息数据模型
├── service/                    # 18 个服务类（详见第 7 节）
└── util/                       # 工具类
```

### 6.2 Application 入口

[App.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/App.java)：
- 全局单例 `mApp`，提供应用级 Context
- 安装全局未捕获异常处理器，崩溃堆栈写入 `getExternalFilesDir(null)/crashes/crash_*.txt`
- 提供全局共享 `OkHttpClient`（15s 超时）

### 6.3 MainActivity（首页）

[MainActivity.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/activity/MainActivity.kt)：

**职责**：数字人模型选择、下载、版本检查、引擎设置。

**主要流程**：
1. `onCreate` → 绑定视图 → 设置版本信息 → 刷新模型状态 → 检查更新
2. **模型卡片**：两个数字人（Xiaoben 小本 / Airuike 艾瑞克），点击选择或下载
3. **下载流程**：先下载基础配置 `gj_dh_res.zip`，再下载具体模型 zip，通过 `ModelManager` 多线程下载 + 解压
4. **版本检查**：从 `https://www.enlyai.com/downloads/duix/version.json` 拉取，对比 versionCode 提示更新
5. **引擎设置**：LLM 引擎切换（Agnes AI / MiMo）、TTS 引擎切换，持久化到 SharedPreferences
6. **进入对话**：校验模型就绪后启动 CallActivity

### 6.4 CallActivity（核心对话页）

[CallActivity.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/activity/CallActivity.kt)：

**规模**：约 3430 行，96 个方法，是整个应用的核心。

**类声明**：
```kotlin
class CallActivity : BaseActivity(), TestHost, PipelineHealthMonitor.HealthHost
```

**核心职责**：
1. **状态机管理**：IDLE → LISTENING → THINKING → SPEAKING → IDLE
2. **ASR 集成**：录音、实时字幕、文字稳定检测、防回声
3. **LLM 集成**：流式 SSE、按句切分送 TTS、实时 AI 字幕
4. **流式 TTS 协调器**：多句合成的 startPush/stopPush 时序管理
5. **DUIX SDK 集成**：初始化、PCM 推送、渲染回调
6. **UI 更新**：状态指示器、消息气泡、字幕淡入淡出
7. **健康监控与自测**：卡死检测、自动恢复、端到端自测

详细架构见 [第 9 节](#9-状态机与流式-tts-协调器)。

---

## 7. 服务层架构

### 7.1 配置中心

[AiConfig.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AiConfig.kt) — `object` 单例，集中管理所有 API Key 和 URL：

| 服务 | 引擎 | 端点 |
|------|------|------|
| ASR | DashScope `fun-asr-realtime` | `wss://dashscope.aliyuncs.com` |
| TTS | Qwen `qwen3-tts-flash-realtime` | WebSocket，24kHz PCM |
| TTS | MiMo `mimo-v2.5-tts` | REST API |
| TTS | Edge TTS | `wss://speech.platform.bing.com`，免费 |
| TTS | Android TTS | `android.speech.tts.TextToSpeech` |
| LLM | Agnes AI `agnes-2.0-flash` | REST + SSE |
| LLM | MiMo `mimo-v2.5` | REST + SSE |

### 7.2 LLM 服务

[LlmService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/LlmService.kt)：

- 支持 Agnes AI / MiMo 多引擎，`switchEngine()` 动态切换
- **流式 SSE**：解析 `data: ` 前缀的 Server-Sent Events
- **重试机制**：MAX_RETRIES=3，5xx 错误重试
- **对话历史管理**：`trimHistory()` 保留 system + 最近 20 轮对话
- **回调接口**：`onToken`（流式 token）、`onComplete`、`onError`

### 7.3 ASR 服务（混合架构）

[HybridAsrService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/HybridAsrService.kt)：

- **优先 DashScope WebSocket ASR**，失败时经 `AsrFallbackManager` 决策后 fallback 到 Android 原生
- **录音参数**：PCM 16kHz mono 16bit，`VOICE_RECOGNITION` 音源
- **录音循环**：单线程 Executor，buffer 取 minBufferSize 的 4 倍
- **音频能量**：每 3 包计算一次 RMS，归一化到 0.0~1.0 供波形可视化

[AsrService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AsrService.kt)（DashScope ASR）：
- WebSocket 连接 `wss://dashscope.aliyuncs.com/api-ws/v1/inference/`
- **协议**：`run-task` → `task-started` → `result-generated`（partial/final）→ `finish-task`
- **自动重连**：指数退避（1/2/4/8/16s），最多 5 次

[AndroidAsrService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AndroidAsrService.kt)（原生 fallback）：
- 基于 `android.speech.SpeechRecognizer`
- **自动重启**：SPEECH_TIMEOUT/NO_MATCH/SERVER 错误时自动重启（最多 3 次）
- 语言：`zh-CN`

[AsrFallbackManager.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AsrFallbackManager.kt)：
- **纯函数决策器**，无副作用
- `decide(context)` 返回 Action：鉴权错 → DISABLE_ASR_USE_TEXT，网络错 → FALLBACK_TO_ANDROID，NoMatch → RETRY_AUTO_LISTEN

### 7.4 TTS 服务（四引擎 + 单向 fallback）

**fallback 链路**：Qwen TTS → MiMo TTS → Edge TTS → Android TTS（绝不反向）

#### QwenTtsService（流式 WebSocket）

[QwenTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/QwenTtsService.kt)：
- WebSocket 连接，**实时流式 TTS**（`qwen3-tts-flash-realtime`，24kHz PCM）
- **协议**：`session.update`（设音色）→ `input_text_buffer.append` → `commit` → `response.audio.delta`（Base64 PCM）→ `response.done`
- **会话 ID 隔离**：`AtomicInteger sessionId`，旧 WebSocket 回调因 ID 不匹配被忽略
- **无数据超时**：15s 内未收到音频则断开重试

#### MimoTtsService（REST）

[MimoTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/MimoTtsService.kt)：
- **REST API**（非流式），OpenAI 兼容格式
- 认证头用 `api-key`（非 `Authorization: Bearer`）
- 响应解析 `choices[0].message.audio.data`（Base64 PCM）
- 读取超时 45s（长文本合成慢）

#### EdgeTtsService（免费 WebSocket）

[EdgeTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/EdgeTtsService.kt)：
- 微软 Edge 浏览器免费 TTS，**无需 API Key**
- WebSocket 连接 `wss://speech.platform.bing.com/.../readaloud/edge/v1`
- **协议**：发送 `speech.config` + SSML → 收集二进制音频 chunk → `turn.end` 合并
- 输出 **MP3** 格式（需 `Mp3ToPcmConverter` 转换）
- 5 个预设音色（Xiaoxiao/Xiaoyi/Yunyang/Yunxi/Jenny）

#### AndroidTtsService（原生）

[AndroidTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AndroidTtsService.kt)：
- 基于 `android.speech.tts.TextToSpeech`
- **双模式**：`synthesizeViaFile`（WAV → PCM）和 `speakDirect`（直接播放 + 静音 PCM 驱动口型）
- 内置 WAV 解析、声道转换、重采样

### 7.5 音频处理

[PcmResampler.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/PcmResampler.kt) — `object` 单例：
- `resample(data, sourceRate, targetRate)`：线性插值重采样（24kHz→16kHz）
- `toMono(data, sourceChannels)`：多声道转单声道
- DUIX SDK 内部 MFCC_RATE=16000

[Mp3ToPcmConverter.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/Mp3ToPcmConverter.kt)：
- 用 `MediaExtractor` + `MediaCodec` 解码 MP3 → PCM
- 重采样到 16kHz mono 16bit

### 7.6 健康监控与自测

[PipelineHealthMonitor.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/PipelineHealthMonitor.kt)：
- **运行时健康监控**，每 10s 检查一次
- **卡死检测**：THINKING 60s / SPEAKING 60s / LISTENING 120s
- **自动修复**：SPEAKING 卡死 → TTS 引擎降级（60s 冷却）
- **健康评分**：0-100，扣分项（卡死次数×15、LLM 超时×10、网络-20、SDK-15、TTS-10）

[PipelineSelfTest.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/PipelineSelfTest.kt)：
- **端到端自测引擎**，模拟用户行为跑通 ASR→LLM→TTS→数字人 全链路
- **4 种测试模式**：TEXT_ONLY / WITH_ASR / TTS_ENGINE_STRESS / RAPID_MULTI_ROUND
- **自动修复**：失败时切换 TTS 引擎重试 / 强制恢复 IDLE 重试

### 7.7 模型下载管理

[ModelManager.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/ModelManager.kt)：
- 委托 `MultiThreadDownloader` + SDK 的 `ZipUtil`
- 检查基础配置/模型就绪
- 下载 → 解压到 `ExternalFilesDir/duix/model/{dirName}/`

[MultiThreadDownloader.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/MultiThreadDownloader.kt)：
- **多线程分片下载** + **断点续传**（Range 请求）
- 每分片独立重试（5 次，退避）

### 7.8 悬浮窗服务

[FloatingWindowService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/FloatingWindowService.kt)：
- **可拖拽悬浮窗**（80x80dp），跨 App 显示数字人图标
- 前台服务（Android 14+ `specialUse` 类型）
- 长按 300ms 拖拽 / 短按回 CallActivity / 右上角 X 关闭 / 边缘吸附

---

## 8. UI 层架构

### 8.1 消息系统

[MessageData.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/MessageData.kt)：

```kotlin
data class MessageData(
    val role: Role,          // USER / AI / SYSTEM
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isThinking: Boolean = false
)
```

[MessageAdapter.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/adapter/MessageAdapter.kt)：
- RecyclerView 适配器，3 种 item 布局（user 右对齐 / ai 左对齐 / system 居中）
- **流式更新优化**：`updateLast` 用 payload 局部更新，避免闪烁；禁用 itemAnimator
- **AI 消息 Markdown 渲染**（`MarkdownRenderer`）
- **思考中动画**：三圆点跳动（`thinking_dot_bounce`，错开 0/200/400ms）
- **长按菜单**：复制/重新生成/点赞/点踩/分享

### 8.2 布局结构

[activity_call.xml](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/res/layout/activity_call.xml) 的主要组件：

```
ConstraintLayout
├── iv_bg                    # 背景图
├── glTextureView            # 数字人渲染（全屏）
├── watermarkCover            # 底部水印遮罩
├── tapOverlay               # 点击打断层
├── tvInterruptHint          # 打断提示
├── toolbar                  # 顶部工具栏（返回/新对话/悬浮窗/设置/静音）
├── tvStatus                 # 状态指示药丸
├── messagesList             # 消息列表（RecyclerView）
├── quickActionsScroll       # 快捷指令 chips
├── bottomPanel              # 底部面板
│   ├── stateIndicatorRow   # 状态图标行
│   ├── errorBanner         # 错误横幅
│   ├── etInput + btnSend   # 文本输入
│   └── btnMic              # 麦克风按钮（带脉冲动画）
└── loadingOverlay           # 加载遮罩
```

### 8.3 动画资源

10 个动画文件（`res/anim/`）：
- `fade_in_up.xml` / `fade_out_down.xml` — 淡入淡出
- `pulse_recording.xml` / `pulse_speaking.xml` — 脉冲动画
- `thinking_dot_bounce.xml` / `thinking_dots.xml` — 思考中圆点
- `tap_ripple_expand.xml` — 点击涟漪
- `slide_in_top.xml` / `slide_out_top.xml` — 错误横幅滑入滑出

### 8.4 主题与配色

- 品牌色：紫罗兰 `#7C3AED` → 青色 `#06B6D4` 渐变
- 深色背景 `#06060F`
- 主题：`Theme.DUIX.Test`（AppCompat 深色科技风）+ `Theme.DUIX.Main`（Material）

---

## 9. 状态机与流式 TTS 协调器

### 9.1 状态机

**四状态**：

```
enum class State { IDLE, LISTENING, THINKING, SPEAKING }
```

**合法转换**：

```
IDLE ──→ LISTENING ──→ THINKING ──→ SPEAKING ──→ IDLE
 │           │            │            │
 └───────────┴────────────┴────────────┘（任何状态 → IDLE 都合法）
```

**`setState(newState)` 核心逻辑**（[CallActivity.kt#L155](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/activity/CallActivity.kt#L155)）：

1. **自动修正非法转换**：如 IDLE→SPEAKING 自动插入 THINKING 中间态
2. **进入 SPEAKING**：取消所有待执行的 auto-listen（防回声）
3. **字幕淡入淡出**：IDLE 时 4s 后淡出，活跃状态时淡入显示
4. **TTS 引擎恢复**：回 IDLE 时恢复用户选择的引擎（fallback 只是临时降级）
5. **通知监听器**：stateListeners（自测用）和 healthMonitor

**超时保护**：

| 超时 | 时长 | 行为 |
|------|------|------|
| SPEAKING_TIMEOUT | 4000ms | 流式模式下按句重置，强制恢复 IDLE |
| THINKING_TIMEOUT | 12000ms | LLM 无响应时恢复 IDLE |
| TTS 完成恢复 | 2000ms | stopPush 后未收到 AUDIO_PLAY_END 则恢复 |

### 9.2 流式 TTS 协调器（核心创新）

**问题**：LLM 流式输出多个句子时，每个句子的 TTS 合成独立调用 `startPush()`/`stopPush()`，导致：
1. 第一句合成完就 `stopPush()`，后续句子的 PCM 数据无人消费
2. `startPush()` 被多次调用，清空播放队列

**解决方案**：三个状态变量协调多句合成（[CallActivity.kt#L338](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/activity/CallActivity.kt#L338)）：

```kotlin
private val streamingPushStarted = AtomicBoolean(false)   // startPush 只调一次
private val streamingTtsPending = AtomicInteger(0)         // 待完成句子计数
@Volatile private var streamingLlmComplete = false          // LLM 是否完成
```

**协调逻辑**：

```
LLM onToken 检测句末标点
    │
    ▼
onStreamingTtsSentenceStart()
    ├── streamingTtsPending +1
    └── 重置 SPEAKING 超时（防止长回复被误判）
    │
    ▼
streamSynthesize(sentence)
    └── TTS 引擎合成 PCM
        ├── 首次：streamingPushStarted.compareAndSet(false, true) → startPush()
        ├── pushPcm(pcmData)
        └── onComplete: onStreamingTtsSentenceDone(tag)
            ├── streamingTtsPending -1
            └── if (llmComplete && pending <= 0)
                └── stopPush() + scheduleTtsCompletionRecovery()
    │
    ▼
LLM onComplete
    └── onStreamingLlmComplete()
        ├── streamingLlmComplete = true
        └── if (pending <= 0 && pushStarted)
            └── stopPush() + scheduleTtsCompletionRecovery()
```

**关键设计**：
- `startPush()` 在第一句 PCM 到达时调用一次
- `stopPush()` 在 LLM 完成且所有 TTS 句子完成时调用一次
- 流式模式下 TTS 错误不触发 fallback 链（避免 pending 计数混乱），直接标记本句完成

### 9.3 防回声设计

- SPEAKING 状态丢弃所有 ASR 结果
- 数字人说话结束后延迟 900ms（`POST_SPEAKING_LISTEN_DELAY_MS`）再启动监听
- 进入 SPEAKING 时取消所有待执行的 auto-listen

### 9.4 字幕自动淡出

**对标豆包/千问**：对话结束后字幕不永久占据屏幕，回归沉浸式数字人视图。

- IDLE 状态 4 秒后自动淡出消息列表（400ms 动画）
- 进入 LISTENING/THINKING/SPEAKING 时立即淡入显示（300ms 动画）
- `playGreeting()` 和 `startNewChat()` 显式调用 `showSubtitles()` 确保可见

---

## 10. CI/CD 与部署架构

### 10.1 整体流程

```
[开发者 push main]
        │
        ▼
[GitHub Actions: build-and-deploy.yml]
        │
        ├─ 1. Checkout 代码
        ├─ 2. 配置 JDK 17 + Android SDK + NDK + CMake
        ├─ 3. 解码签名 keystore (secrets.KEYSTORE_BASE64)
        ├─ 4. 写入 local.properties (keystore 配置)
        ├─ 5. 自动递增 versionCode/versionName
        ├─ 6. Gradle 构建 Release APK
        ├─ 7. 生成 version.json
        ├─ 8. 配置 SSH key (secrets.ECS_SSH_KEY)
        ├─ 9. SCP 上传到阿里云 ECS
        ├─ 10. Python 脚本注入 Nginx /downloads/ location
        ├─ 11. nginx -t && nginx -s reload
        ├─ 12. 回写 versionCode 到 main 分支并 push
        └─ 13. 上传 APK artifact (保留30天)
                │
                ▼
[阿里云 ECS 114.215.183.45]
   /var/www/enlyai.com/downloads/duix/
        │
        ▼
[Nginx] → https://www.enlyai.com/downloads/duix/
   ├── index.html                    (下载落地页)
   ├── duix_digital_human.apk        (APK 安装包)
   ├── version.json                  (应用内更新检查)
   └── models/                       (数字人模型下载源)
```

### 10.2 构建工作流

[build-and-deploy.yml](file:///workspace/Duix-Mobile/.github/workflows/build-and-deploy.yml)：

**触发条件**：
- push 到 `main` 分支自动触发（主要方式）
- 手动 `workflow_dispatch`（仅 CI 失败需重建时使用）

**权限**：`contents: write`（回写版本号）

**关键步骤**（12 步）：

| 步骤 | 说明 |
|------|------|
| Checkout | `actions/checkout@v4` |
| Setup JDK 17 | `actions/setup-java@v4`，temurin |
| Setup Android SDK | `android-actions/setup-android@v3` |
| Install CMake and NDK | cmake;3.18.1、ndk;25.2.9519653 |
| Decode keystore | 从 secrets 解码 `demo.jks` |
| Configure local.properties | 写入 sdk.dir、keystore 配置 |
| Bump version | 自动递增 versionCode，versionName=`主版本.次版本.versionCode` |
| Build Release APK | `./gradlew :test:clean :test:assembleRelease --no-daemon` |
| Generate version.json | 含 version_code/download_url/update_message 等 |
| Setup SSH key | 从 secrets.ECS_SSH_KEY 写入 |
| Deploy to ECS | SCP 上传 + Python 修改 nginx 配置 + reload |
| Commit version bump | github-actions[bot] 提交 versionCode 变更 |

**Secrets 依赖**：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS_PASSWORD`、`ECS_SSH_KEY`、`ECS_HOST`、`ECS_PORT`、`ECS_USER`

### 10.3 诊断工作流

[diag-ecs.yml](file:///workspace/Duix-Mobile/.github/workflows/diag-ecs.yml)：

仅手动触发，6 项检查：Nginx 状态、下载目录、APK 文件、version.json、磁盘空间、URL 可达性。

### 10.4 ECS 初始化

[setup-ecs.sh](file:///workspace/Duix-Mobile/deploy/setup-ecs.sh)（一次性运行）：

1. 添加 GitHub Actions SSH 公钥
2. 安装 Nginx
3. 创建下载目录 `/var/www/enlyai.com/downloads/duix`
4. 配置 Nginx（`/downloads/` alias + CORS + 200M body）
5. 测试并重载 Nginx
6. 开放防火墙 80 端口

### 10.5 下载落地页

[download-page.html](file:///workspace/Duix-Mobile/deploy/download-page.html)：

- 深色渐变背景的现代响应式 UI
- 下载按钮指向 `duix_digital_human.apk`
- 动态加载 `version.json` 显示版本号
- 4 个特性卡片：Voice Chat / Agnes AI / Edge TTS / Digital Human

### 10.6 项目规则

[project_rules.md](file:///workspace/Duix-Mobile/.trae/rules/project_rules.md) 关键约定：

- **版本号**：CI 自动递增 versionCode，禁止手动修改
- **CI 触发**：push 到 main 自动触发，禁止再手动 workflow_dispatch
- **测试通知**：必须确认 CI conclusion=success 且 APK 已部署
- **TTS 引擎**：优先 Edge TTS，失败自动切换 Android TTS
- **模型缓存**：存储在 `getExternalFilesDir("duix")/model/`，覆盖安装不删除

---

## 11. 跨平台架构（iOS 端）

### 11.1 整体定位

iOS 端作为 Android 端的跨平台对应实现，功能对等，提供实时交互式 AI 数字人能力。

**路径**：`duix-ios/GJLocalDigitalDemo/`

### 11.2 技术选型对比

| 层级 | Android | iOS |
|------|---------|-----|
| 渲染 | OpenGL ES 2.0 | Metal（`shaders.metal`） |
| 推理 | ncnn + ONNX Runtime | ncnn + ONNX Runtime（CoreML 加速） |
| 图像处理 | OpenCV 4.6.0 | OpenCV 2 |
| 音频采集 | AudioRecord + WebRTC | KFAudioCapture + WebRTC 噪声抑制 |
| 语言 | Kotlin + Java + C++ | Objective-C++（.mm） |

### 11.3 iOS SDK 结构

```
GJLocalDigitalSDK/
├── AudioCapture/              # 音频采集（WebRTC、KFAudioCapture、噪声抑制）
├── DIMetalView/                # Metal 渲染视图（shaders.metal）
├── DigitalHumanDriven/         # 数字人驱动核心
├── GCDTimer/                   # 定时器
└── GJFrameWork/                # 依赖框架
    ├── GJLDecry.framework/     # 解密框架
    ├── ncnn.framework/         # 神经网络推理
    ├── onnxruntime.framework/  # ONNX Runtime（含 CoreML/CPU provider）
    └── opencv2.framework/      # OpenCV
```

**注意**：iOS 端**未纳入当前 CI/CD 流程**，仍需通过 Xcode 手动构建。

---

## 12. 关键设计决策与亮点

### 12.1 流式 TTS 架构

**决策**：LLM 流式输出与 TTS 合成流水线并行，按句切分提前送合成。

**收益**：首字延迟从 2-4 秒降至 300-500ms，对标豆包/千问等专业产品。

**实现**：三个原子变量（`streamingPushStarted`/`streamingTtsPending`/`streamingLlmComplete`）协调多句合成的 startPush/stopPush 时序。

### 12.2 四引擎 TTS + 单向 fallback

**决策**：Qwen → MiMo → Edge → Android，绝不反向。

**收益**：兼顾音质（Qwen 流式实时）、稳定性（多级 fallback）、成本（Edge 免费）、可用性（Android 原生兜底）。

### 12.3 状态机自动修正

**决策**：非法转换自动插入中间态（如 IDLE→SPEAKING 插入 THINKING）。

**收益**：避免状态混乱导致的 UI 异常和音频回声。

### 12.4 音频播放进度与口型帧同步

**决策**：`AudioPlayer.getPlayIndex()` 返回的索引直接传给 native `filerst(bnfIndex)`。

**收益**：实现数字人口型与声音的精确对齐，这是数字人体验的核心。

### 12.5 端侧渲染 + 云端 AI

**决策**：渲染引擎（ncnn + UNet + OpenGL）完全本地运行，LLM/ASR/TTS 走云端 API。

**收益**：弱网环境下数字人仍可渲染（用本地静音 PCM 驱动口型动画），核心体验不依赖网络。

### 12.6 运行时健康监控 + 自动修复

**决策**：每 10s 检查状态卡死，SPEAKING 卡死自动降级 TTS 引擎。

**收益**：长时间运行稳定性，无需用户干预自动恢复。

### 12.7 端到端自测引擎

**决策**：4 种测试模式（TEXT_ONLY/WITH_ASR/TTS_ENGINE_STRESS/RAPID_MULTI_ROUND），失败自动重试。

**收益**：CI 无法覆盖的运行时管线问题可在真机自测发现。

### 12.8 字幕自动淡出 + 极简 IDLE 界面

**决策**：IDLE 4s 后淡出消息列表，IDLE 时隐藏顶部状态药丸。

**收益**：对标豆包/千问的沉浸式数字人视图，对话结束后聚焦数字人本身。

---

## 13. 附录：关键文件清单

### 13.1 SDK 层

| 类别 | 文件 |
|------|------|
| 公共 API | [DUIX.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/DUIX.java) |
| 回调接口 | [Callback.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/Callback.java) |
| 事件常量 | [Constant.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/Constant.java) |
| Native 声明 | [DuixNcnn.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/DuixNcnn.java) |
| 渲染容器 | [DUIXTextureView.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/render/DUIXTextureView.java) |
| 渲染管道 | [DUIXRenderer.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/render/DUIXRenderer.java) |
| 着色器 | [ImageDrawer.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/render/ImageDrawer.java) |
| 音频播放 | [AudioPlayer.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/java/ai/guiji/duix/sdk/client/audio/AudioPlayer.java) |
| JNI 桥接 | [DuixJni.cpp](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/cpp/android/DuixJni.cpp) |
| C++ 编排 | [gjsimp.cpp](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/cpp/duix/gjsimp.cpp) |
| CMake | [CMakeLists.txt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/duix-sdk/src/main/cpp/CMakeLists.txt) |

### 13.2 应用层

| 类别 | 文件 |
|------|------|
| Application | [App.java](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/App.java) |
| 首页 | [MainActivity.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/activity/MainActivity.kt) |
| 对话页 | [CallActivity.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/activity/CallActivity.kt) |
| 消息数据 | [MessageData.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/MessageData.kt) |
| 消息适配器 | [MessageAdapter.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/ui/adapter/MessageAdapter.kt) |
| 布局 | [activity_call.xml](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/res/layout/activity_call.xml) |

### 13.3 服务层

| 类别 | 文件 |
|------|------|
| 配置中心 | [AiConfig.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AiConfig.kt) |
| LLM | [LlmService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/LlmService.kt) |
| ASR 混合 | [HybridAsrService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/HybridAsrService.kt) |
| ASR DashScope | [AsrService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AsrService.kt) |
| ASR 原生 | [AndroidAsrService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AndroidAsrService.kt) |
| TTS Qwen | [QwenTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/QwenTtsService.kt) |
| TTS MiMo | [MimoTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/MimoTtsService.kt) |
| TTS Edge | [EdgeTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/EdgeTtsService.kt) |
| TTS Android | [AndroidTtsService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/AndroidTtsService.kt) |
| PCM 重采样 | [PcmResampler.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/PcmResampler.kt) |
| MP3 转换 | [Mp3ToPcmConverter.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/Mp3ToPcmConverter.kt) |
| 健康监控 | [PipelineHealthMonitor.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/PipelineHealthMonitor.kt) |
| 自测引擎 | [PipelineSelfTest.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/PipelineSelfTest.kt) |
| 模型下载 | [ModelManager.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/ModelManager.kt) |
| 悬浮窗 | [FloatingWindowService.kt](file:///workspace/Duix-Mobile/duix-android/dh_aigc_android/test/src/main/java/ai/guiji/duix/test/service/FloatingWindowService.kt) |

### 13.4 CI/CD 与部署

| 类别 | 文件 |
|------|------|
| 构建部署 | [build-and-deploy.yml](file:///workspace/Duix-Mobile/.github/workflows/build-and-deploy.yml) |
| 诊断 | [diag-ecs.yml](file:///workspace/Duix-Mobile/.github/workflows/diag-ecs.yml) |
| ECS 初始化 | [setup-ecs.sh](file:///workspace/Duix-Mobile/deploy/setup-ecs.sh) |
| 下载页 | [download-page.html](file:///workspace/Duix-Mobile/deploy/download-page.html) |
| 项目规则 | [project_rules.md](file:///workspace/Duix-Mobile/.trae/rules/project_rules.md) |
| README | [README.md](file:///workspace/Duix-Mobile/README.md) / [README_zh.md](file:///workspace/Duix-Mobile/README_zh.md) |

---

## 文档结束

本文档基于 DUIX Mobile 4.4.77 版本编写，涵盖从 C++ Native 引擎到 CI/CD 部署的完整架构。如需了解特定模块的更多细节，请参考附录中的文件链接或联系项目维护者。
