# ApiAPK - AI Model I/O Capture & API Service

<p align="center">
  <strong>🚀 Android端AI对话捕获 + 本地API服务 + NPM CLI工具</strong>
</p>

<p align="center">
  捕获 DeepSeek · 豆包 · 小爱同学 的对话输入输出，转换为RESTful API服务
</p>

---

## 📋 功能特性

### Android APK
- 🎯 **无障碍服务捕获** - 自动捕获DeepSeek、豆包、小爱同学的对话内容
- 📡 **本地HTTP服务器** - NanoHTTPD轻量级API服务，端口可配置
- 🔄 **ADB集成** - 通过ADB向AI应用发送消息，自动化输入
- 💾 **持久化存储** - 会话数据本地存储，支持容量管理
- 🔐 **API密钥认证** - 可选的API密钥保护
- ⚙️ **灵活配置** - 端口、存储上限、日志级别等可调

### NPM CLI (apiapk)
- 🖥️ **命令行工具** - 连接、查询、发送、监控一体化
- 🔄 **API代理** - 本地OpenAI兼容API代理服务器
- 📡 **WebSocket** - 实时消息推送
- 📊 **美化输出** - 表格化展示会话和统计信息

## 🏗️ 项目结构

```
apiapk/
├── android-app/                  # Android APK 项目
│   ├── app/src/main/
│   │   ├── java/com/apiapk/
│   │   │   ├── model/           # 数据模型
│   │   │   │   ├── Models.kt    # AIConversation, AIMessage, ApiConfig等
│   │   │   │   └── ConversationStore.kt  # 持久化存储
│   │   │   ├── service/         # 核心服务
│   │   │   │   ├── AICaptureService.kt    # 无障碍服务-捕获AI对话
│   │   │   │   ├── ApiServerService.kt    # HTTP API服务器
│   │   │   │   └── BootReceiver.kt        # 开机自启
│   │   │   ├── ui/              # 界面
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── SettingsActivity.kt
│   │   │   │   └── LogActivity.kt
│   │   │   └── util/
│   │   │       └── AdbHelper.kt  # ADB命令封装
│   │   └── res/                  # 资源文件
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── npm-cli/                      # NPM CLI 工具
│   ├── bin/cli.js               # CLI入口
│   ├── lib/
│   │   ├── client.js            # API客户端
│   │   ├── proxy-server.js      # 代理服务器(OpenAI兼容)
│   │   ├── display.js           # 终端显示工具
│   │   └── index.js             # 模块导出
│   └── package.json
│
├── Dockerfile                    # 构建环境
└── README.md                     # 项目文档
```

## 📡 API 端点

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/status` | 服务器状态 |
| GET | `/api/conversations` | 所有会话列表 |
| GET | `/api/conversations/{app}` | 按应用过滤 (deepseek/doubao/xiaoai) |
| GET | `/api/conversations/{id}` | 会话详情 |
| POST | `/api/send/{app}` | 发送消息到AI应用 |
| DELETE | `/api/conversations/{id}` | 删除会话 |
| GET | `/api/stats` | 捕获统计 |
| GET/POST | `/api/config` | 查看/更新配置 |
| POST | `/api/adb/command` | 执行ADB命令 |
| GET | `/api/adb/devices` | ADB设备列表 |
| POST | `/api/adb/shell` | 执行Shell命令 |

## 🚀 使用方式

### Android端

1. **安装APK** - 编译并安装到Android设备
2. **开启无障碍服务** - 设置 → 无障碍 → ApiAPK → 开启
3. **启动服务器** - 在应用中点击"启动服务器"
4. **打开AI应用** - 使用DeepSeek/豆包/小爱，对话会自动捕获

### NPM CLI

```bash
# 安装
npm install -g apiapk

# 连接（手机IP）
apiapk connect 192.168.1.100

# 查看状态
apiapk status

# 列出所有会话
apiapk list
apiapk list --app deepseek

# 查看会话详情
apiapk get <conversation-id>

# 发送消息
apiapk send deepseek "你好"
apiapk send doubao "翻译一下这段话"
apiapk send xiaoai "今天天气怎么样"

# 查看统计
apiapk stats

# 实时监听
apiapk watch

# 启动代理服务器（OpenAI兼容）
apiapk serve --port 3000 --proxy-host 192.168.1.100

# 执行ADB命令
apiapk adb "devices"
apiapk adb "shell dumpsys battery" --shell
```

### OpenAI兼容API

通过代理服务器，可以将捕获的AI对话以OpenAI格式提供：

```bash
# 获取可用模型
curl http://localhost:3000/v1/models

# 发送对话请求
curl http://localhost:3000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'

# WebSocket实时推送
const ws = new WebSocket('ws://localhost:3000/ws');
ws.onmessage = (e) => console.log(JSON.parse(e.data));
```

## 🔧 Android APK 构建

```bash
cd android-app
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 📦 依赖

### Android
- NanoHTTPD 2.3.1 - 轻量级HTTP服务器
- Gson 2.10.1 - JSON序列化
- AndroidX - UI框架
- Kotlin Coroutines - 异步处理

### NPM CLI
- commander - CLI框架
- axios - HTTP客户端
- chalk - 终端彩色输出
- cli-table3 - 终端表格
- express - 代理服务器
- ws - WebSocket

## ⚠️ 注意事项

1. **无障碍权限** - 必须开启无障碍服务才能捕获AI对话
2. **ADB发送** - 通过ADB发送消息需要Root权限或ADB调试授权
3. **网络** - 手机和电脑需在同一WiFi网络
4. **隐私** - 所有数据仅存储在本地设备，不会上传

## 📄 License

MIT License
