# ApiAPK - AI Model I/O Capture & API Service

<p align="center">
  <strong>🚀 Android端AI对话捕获 → 本地RESTful API服务 + SSE流式输出</strong>
</p>

<p align="center">
  捕获 DeepSeek · 豆包 · 小爱同学 的对话输入输出，在手机上直接产生API接口<br>
  <strong>v2.0</strong> — 流式SSE · 分屏支持 · 后台持久 · OpenAI兼容 · UI探测器
</p>

---

## ✨ v2.0 新功能

| 功能 | 说明 |
|------|------|
| 🌊 **SSE流式输出** | AI模型逐token输出时，通过SSE实时推送增量（delta），与DeepSeek/豆包原生体验一致 |
| 📱 **分屏支持** | 同时分屏运行豆包+DeepSeek，两个APP的对话都被独立捕获 |
| 🔋 **后台持久运行** | 前台服务 + WakeLock + 电池优化白名单 + 开机自启 + 心跳监控四重保障 |
| 🤖 **OpenAI兼容API** | `/v1/chat/completions` 端点，可直接用OpenAI SDK连接，支持 `stream=true` |
| 🔬 **UI探测器** | `/api/ui/dump` 端点，dump当前窗口的完整UI节点树，用于调试和发现控件ID |
| 🧠 **启发式捕获** | 不硬编码任何resource-id，通过节点树遍历+启发式规则自动发现聊天区域 |

---

## 📡 API 端点

安装APK并启动服务器后，手机上会运行一个HTTP服务（默认端口 `8765`）：

### 会话管理
| 方法 | 路径 | 描述 |
|------|------|------|
| `GET` | `/api/status` | 服务器状态（版本、SSE连接数、捕获统计） |
| `GET` | `/api/conversations` | 所有会话摘要列表（支持分页） |
| `GET` | `/api/conversations/{app}` | 按应用过滤：`deepseek` / `doubao` / `xiaoai` |
| `GET` | `/api/conversations/{id}` | 指定会话的完整对话 |
| `POST` | `/api/send/{app}` | 向AI应用发送消息 |
| `DELETE` | `/api/conversations/{id}` | 删除指定会话 |

### 🌊 SSE 流式端点（v2.0 新增）
| 方法 | 路径 | 描述 |
|------|------|------|
| `GET` | `/api/stream` | SSE流：接收所有AI应用的增量输出 |
| `GET` | `/api/stream/{app}` | SSE流：指定应用（如 `/api/stream/deepseek`） |

SSE事件格式：
```
event: delta
data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"你好","accumulated":"你好"}

event: delta
data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"，","accumulated":"你好，"}

event: finish
data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"","accumulated":"你好，我是DeepSeek","finishReason":"stop"}
```

### 🔬 UI 探测器（v2.0 新增）
| 方法 | 路径 | 描述 |
|------|------|------|
| `GET` | `/api/ui/dump` | Dump当前窗口完整UI节点树（调试用） |
| `GET` | `/api/ui/snapshot` | 当前窗口文本快照 |

### 🤖 OpenAI 兼容（v2.0 新增）
| 方法 | 路径 | 描述 |
|------|------|------|
| `GET` | `/v1/models` | 模型列表（deepseek, doubao, xiaoai） |
| `POST` | `/v1/chat/completions` | Chat Completions，支持 `stream: true` |

### 系统管理
| 方法 | 路径 | 描述 |
|------|------|------|
| `GET` | `/api/stats` | 捕获统计 |
| `GET/POST` | `/api/config` | 配置管理（含 `inspectorMode` 开关） |
| `POST` | `/api/adb/command` | 执行ADB命令 |
| `GET` | `/api/adb/devices` | ADB设备列表 |
| `POST` | `/api/adb/shell` | 执行Shell命令 |

---

## 🚀 使用方式

### 1. 安装APK
下载 [app-debug.apk](./android-app/app-debug.apk) 并安装到 Android 手机（需开启"允许安装未知来源"）。

### 2. 开启无障碍服务
设置 → 无障碍 → 找到 **ApiAPK** → 开启

> 这是核心步骤，开启后才能捕获AI应用的对话内容。

### 3. 启动API服务器
打开 ApiAPK → 点击 **启动服务器**

界面显示服务器地址，例如 `http://192.168.1.100:8765`

### 4. 添加电池优化白名单
点击 **电池优化白名单** → 选择"不优化"

> 防止系统在后台杀掉服务。

### 5. 使用AI应用
正常打开 DeepSeek / 豆包 / 小爱同学 进行对话，内容自动被捕获。

> 💡 **分屏模式**：同时分屏运行两个AI应用（如豆包+DeepSeek），两个APP的对话都会被独立捕获。

### 6. 调用API

```bash
# 查看服务器状态
curl http://PHONE_IP:8765/api/status

# 流式接收DeepSeek的输出（SSE）
curl -N http://PHONE_IP:8765/api/stream/deepseek

# 探测UI节点树（调试用，需先开启inspector模式）
curl -X POST http://PHONE_IP:8765/api/config \
  -H "Content-Type: application/json" \
  -d '{"inspectorMode": true}'
curl http://PHONE_IP:8765/api/ui/dump

# 用OpenAI格式发消息（流式）
curl -N http://PHONE_IP:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

---

## 📱 分屏模式

AccessibilityService 天然支持多窗口。分屏运行豆包+DeepSeek时：
- 两个APP的事件都会被捕获（通过 `packageName` 区分）
- `/api/stream` 可以同时接收两个APP的流式输出
- 每个事件的 `app` 字段标识来源

```
┌─────────────────┬─────────────────┐
│                 │                 │
│   豆包 (Doubao)  │  DeepSeek       │
│                 │                 │
│   (上半屏)       │  (下半屏)       │
│                 │                 │
├─────────────────┴─────────────────┤
│  ApiAPK 后台运行，捕获两个APP     │
│  → SSE推送: app="doubao"/"deepseek"│
└───────────────────────────────────┘
```

---

## 🔬 UI 探测器

由于不同AI应用的UI结构可能因版本而异，ApiAPK v2.0 提供了 **UI探测器** 功能：

1. 开启探测器模式：`POST /api/config {"inspectorMode": true}`
2. 打开目标AI应用
3. 调用 `GET /api/ui/dump` 获取完整的UI节点树
4. 查看每个节点的 `className`、`text`、`viewId`、`isEditable`、`bounds` 等信息

返回示例：
```
LinearLayout
  TextView text="你好，有什么可以帮你？" id=com.deepseek.chat:id/tv_response depth=5
  EditText [EDIT] text="" hint="输入消息" depth=4
  ImageButton [CLICK] desc="发送" depth=4
```

---

## 🔧 编译构建

### 环境要求
- JDK 17+
- Android SDK (API 34)
- Gradle 8.2+

### 编译命令
```bash
git clone https://github.com/ctz168/apiapk.git
cd apiapk/android-app
./gradlew assembleDebug
```

输出文件：`app/build/outputs/apk/debug/app-debug.apk`

---

## 🏗️ 项目结构

```
apiapk/
├── android-app/
│   ├── app/src/main/
│   │   ├── java/com/apiapk/
│   │   │   ├── model/
│   │   │   │   ├── Models.kt              # 数据模型 + StreamDelta
│   │   │   │   ├── StreamEventBus.kt       # 流式事件总线（核心桥梁）
│   │   │   │   └── ConversationStore.kt    # 持久化存储
│   │   │   ├── service/
│   │   │   │   ├── AICaptureService.kt     # 无障碍服务（启发式捕获引擎）
│   │   │   │   ├── ApiServerService.kt     # HTTP服务器（含SSE端点）
│   │   │   │   ├── BackgroundMonitorService.kt  # 后台存活守护
│   │   │   │   ├── BootReceiver.kt         # 开机自启
│   │   │   │   └── UserPresentReceiver.kt  # 解屏恢复
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── SettingsActivity.kt
│   │   │   │   └── LogActivity.kt
│   │   │   └── util/
│   │   │       └── AdbHelper.kt
│   │   └── res/
│   ├── app-debug.apk                        # 编译好的APK
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── README.md
```

---

## ⚠️ 注意事项

1. **无障碍权限** — 必须手动开启
2. **AI应用需可见** — 无障碍服务只能读取可见窗口的内容（前台/分屏/悬浮窗）
3. **同一WiFi** — 调用API的设备和手机需在同一局域网
4. **隐私安全** — 所有数据仅存储在手机本地
5. **首次使用** — 建议先用UI探测器确认目标APP的控件是否被正确识别

## 📄 License

MIT License
