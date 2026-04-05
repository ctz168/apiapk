/**
 * ApiAPK 本地API代理服务器 - 在电脑上运行，将请求转发到手机端的ApiAPK服务。
 * 支持CORS、请求日志、WebSocket实时推送，以及OpenAI兼容的API格式。
 * 可以将手机上的AI对话转换为标准的OpenAI Chat Completion格式供第三方工具使用。
 */

const express = require('express');
const cors = require('cors');
const { WebSocketServer } = require('ws');
const http = require('http');
const ApiApkClient = require('./client');

class ProxyServer {
    /**
     * @param {number} localPort - 本地代理服务器端口
     * @param {string} remoteHost - ApiAPK远程服务器地址（手机IP）
     * @param {number} remotePort - ApiAPK远程服务器端口
     */
    constructor(localPort, remoteHost, remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.app = express();
        this.client = new ApiApkClient(remoteHost, remotePort);
        this.wss = null;
    }

    /**
     * 启动代理服务器
     */
    async start() {
        // 中间件
        this.app.use(cors());
        this.app.use(express.json());
        this.app.use(this.requestLogger.bind(this));

        // 健康检查端点
        this.app.get('/health', (req, res) => {
            res.json({ status: 'ok', proxy: true });
        });

        // 代理所有/api/请求到手机端
        this.app.all('/api/*', this.proxyRequest.bind(this));

        // OpenAI兼容端点 - 将捕获的AI对话以OpenAI格式提供
        this.app.get('/v1/models', this.handleModels.bind(this));
        this.app.post('/v1/chat/completions', this.handleChatCompletions.bind(this));
        this.app.get('/v1/conversations', this.handleListConversations.bind(this));

        // 指南页面
        this.app.get('/', (req, res) => {
            res.send(this.getProxyIndexPage());
        });

        // 创建HTTP服务器并挂载WebSocket
        const server = http.createServer(this.app);

        // WebSocket服务器 - 实时推送新消息
        this.wss = new WebSocketServer({ server, path: '/ws' });
        this.setupWebSocket();

        // 启动服务器
        server.listen(this.localPort, () => {
            console.log(`
  ╔══════════════════════════════════════════════╗
  ║   🚀 ApiAPK Proxy Server Started             ║
  ╠══════════════════════════════════════════════╣
  ║   Local:   http://localhost:${this.localPort}            ║
  ║   Remote:  http://${this.remoteHost}:${this.remotePort}    ║
  ║   WS:      ws://localhost:${this.localPort}/ws           ║
  ╠══════════════════════════════════════════════╣
  ║   Endpoints:                                  ║
  ║   /api/*           → Proxy to Android        ║
  ║   /v1/models       → OpenAI compatible        ║
  ║   /v1/chat/completions → AI proxy            ║
  ║   /ws              → Real-time updates        ║
  ╚══════════════════════════════════════════════╝
            `);
        });

        // 启动消息监听
        this.startWatching();
    }

    /**
     * 请求日志中间件
     */
    requestLogger(req, res, next) {
        const start = Date.now();
        res.on('finish', () => {
            const duration = Date.now() - start;
            const status = res.statusCode;
            const color = status < 300 ? '\x1b[32m' : status < 400 ? '\x1b[33m' : '\x1b[31m';
            console.log(`  ${color}${status}\x1b[0m ${req.method} ${req.path} ${duration}ms`);
        });
        next();
    }

    /**
     * 代理请求到远程ApiAPK服务器
     */
    async proxyRequest(req, res) {
        try {
            const path = req.path;
            const method = req.method.toLowerCase();
            const url = `http://${this.remoteHost}:${this.remotePort}${path}`;

            const axios = require('axios');
            const options = {
                method,
                url,
                headers: { ...req.headers, host: undefined },
                timeout: 30000,
            };

            if (method === 'get' && Object.keys(req.query).length > 0) {
                options.params = req.query;
            }

            if (['post', 'put', 'patch'].includes(method) && req.body) {
                options.data = req.body;
            }

            const response = await axios(options);
            res.status(response.status).json(response.data);
        } catch (err) {
            if (err.response) {
                res.status(err.response.status).json(err.response.data);
            } else {
                res.status(502).json({
                    success: false,
                    message: `Proxy error: ${err.message}`,
                    timestamp: Date.now()
                });
            }
        }
    }

    /**
     * OpenAI兼容 - 返回可用模型列表
     */
    async handleModels(req, res) {
        try {
            const result = await this.client.getStats();
            const byApp = result.data?.byApp || {};

            const models = Object.entries(byApp).map(([app, stats]) => ({
                id: app,
                object: 'model',
                created: Math.floor(Date.now() / 1000),
                owned_by: 'apiapk',
                conversations: stats.conversations,
                messages: stats.messages,
                description: `Captured ${app} AI model conversations`
            }));

            res.json({
                object: 'list',
                data: models
            });
        } catch (err) {
            res.status(502).json({ error: { message: err.message } });
        }
    }

    /**
     * OpenAI兼容 - Chat Completions端点
     * 将捕获的AI对话历史作为上下文，转发消息到指定的AI应用
     */
    async handleChatCompletions(req, res) {
        try {
            const { model, messages, stream } = req.body;

            if (!model || !messages) {
                return res.status(400).json({
                    error: { message: 'Missing required fields: model, messages' }
                });
            }

            // 获取最后一条用户消息
            const lastUserMsg = [...messages].reverse().find(m => m.role === 'user');
            if (!lastUserMsg) {
                return res.status(400).json({
                    error: { message: 'No user message found' }
                });
            }

            // 通过手机端API发送消息到目标AI应用
            const sendResult = await this.client.sendMessage(model, lastUserMsg.content);

            // 构造OpenAI格式的响应
            const conversation = sendResult.data?.conversation;
            const responseMessage = {
                role: 'assistant',
                content: `Message sent to ${model}. Conversation ID: ${conversation?.id}. Use GET /api/conversations/${conversation?.id} to retrieve the AI response.`,
            };

            res.json({
                id: `chatcmpl-${Date.now()}`,
                object: 'chat.completion',
                created: Math.floor(Date.now() / 1000),
                model: model,
                choices: [{
                    index: 0,
                    message: responseMessage,
                    finish_reason: 'stop'
                }],
                usage: {
                    prompt_tokens: messages.reduce((sum, m) => sum + (m.content?.length || 0), 0),
                    completion_tokens: responseMessage.content.length,
                    total_tokens: messages.reduce((sum, m) => sum + (m.content?.length || 0), 0) + responseMessage.content.length
                }
            });
        } catch (err) {
            res.status(502).json({ error: { message: err.message } });
        }
    }

    /**
     * 列出所有对话（OpenAI风格）
     */
    async handleListConversations(req, res) {
        try {
            const result = await this.client.getConversations(100);
            res.json({
                object: 'list',
                data: (result.data?.conversations || []).map(c => ({
                    id: c.id,
                    app: c.app,
                    created: Math.floor(c.startedAt / 1000),
                    last_updated: Math.floor(c.updatedAt / 1000),
                    message_count: c.messageCount
                }))
            });
        } catch (err) {
            res.status(502).json({ error: { message: err.message } });
        }
    }

    /**
     * WebSocket实时消息推送
     */
    setupWebSocket() {
        this.wss.on('connection', (ws) => {
            console.log('  📡 WebSocket client connected');

            ws.on('close', () => {
                console.log('  📡 WebSocket client disconnected');
            });

            ws.on('error', (err) => {
                console.error('  ⚠️ WebSocket error:', err.message);
            });
        });
    }

    /**
     * 启动消息监听，通过WebSocket推送新消息
     */
    startWatching() {
        this.client.watchConversations(null, (conversation) => {
            if (this.wss) {
                const data = JSON.stringify({
                    type: 'new_message',
                    data: conversation,
                    timestamp: Date.now()
                });
                this.wss.clients.forEach(client => {
                    if (client.readyState === 1) { // WebSocket.OPEN
                        client.send(data);
                    }
                });
            }
        }, 2000);
    }

    /**
     * 代理服务器的首页
     */
    getProxyIndexPage() {
        return `
<!DOCTYPE html>
<html><head><title>ApiAPK Proxy</title>
<style>
body{font-family:system-ui,sans-serif;max-width:800px;margin:40px auto;padding:20px;background:#0f0f23;color:#ccc}
h1{color:#ff6b6b} h2{color:#4ecdc4;background:#1a1a2e;padding:10px;border-radius:5px}
code{background:#1a1a2e;padding:2px 6px;border-radius:3px;color:#45b7d1;font-family:monospace}
pre{background:#1a1a2e;padding:15px;border-radius:5px;overflow-x:auto;color:#45b7d1}
.endpoint{margin:6px 0;padding:8px;background:#1a1a2e;border-radius:5px}
.method{color:#ff6b6b;font-weight:bold}
</style></head><body>
<h1>🔄 ApiAPK Proxy Server</h1>
<p>本地代理 → 手机端ApiAPK (http://${this.remoteHost}:${this.remotePort})</p>
<h2>📡 OpenAI Compatible Endpoints</h2>
<div class="endpoint"><span class="method">GET</span> <code>/v1/models</code> - List captured AI models</div>
<div class="endpoint"><span class="method">POST</span> <code>/v1/chat/completions</code> - Send message to AI</div>
<div class="endpoint"><span class="method">GET</span> <code>/v1/conversations</code> - List conversations</div>
<h2>🔌 Proxy Endpoints</h2>
<div class="endpoint"><span class="method">ALL</span> <code>/api/*</code> - Proxy to Android ApiAPK</div>
<div class="endpoint"><span class="method">WS</span> <code>/ws</code> - Real-time message stream</div>
<h2>💡 Usage</h2>
<pre>
# Use with OpenAI-compatible clients
curl http://localhost:${this.localPort}/v1/models

# Send a message to DeepSeek
curl -X POST http://localhost:${this.localPort}/v1/chat/completions \\
  -H "Content-Type: application/json" \\
  -d '{"model":"deepseek","messages":[{"role":"user","content":"Hello!"}]}'

# WebSocket real-time updates
const ws = new WebSocket('ws://localhost:${this.localPort}/ws');
ws.onmessage = (e) => console.log(JSON.parse(e.data));
</pre>
<h2>⚙️ NPM CLI Commands</h2>
<pre>
apiapk connect ${this.remoteHost}
apiapk list --app deepseek
apiapk send doubao "你好"
apiapk watch
apiapk serve --port ${this.localPort}
</pre>
</body></html>`;
    }
}

module.exports = ProxyServer;
