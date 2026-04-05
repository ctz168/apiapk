/**
 * ApiAPK API客户端 - 封装与Android端ApiAPK HTTP服务器的所有通信逻辑。
 * 提供会话管理、消息发送、ADB命令执行、配置管理等功能的Promise化API。
 */

const axios = require('axios');

class ApiApkClient {
    /**
     * @param {string} host - ApiAPK服务器主机地址
     * @param {number} port - ApiAPK服务器端口
     * @param {string} [apiKey] - 可选的API密钥
     */
    constructor(host, port, apiKey) {
        this.baseUrl = `http://${host}:${port}`;
        this.apiKey = apiKey || '';
        this.client = axios.create({
            baseURL: this.baseUrl,
            timeout: 15000,
            headers: {
                'Content-Type': 'application/json',
            },
        });

        if (this.apiKey) {
            this.client.defaults.headers.common['X-API-Key'] = this.apiKey;
        }
    }

    /**
     * 获取服务器运行状态和基本信息
     * @returns {Promise<Object>} 包含版本、运行时间、端点列表等状态信息
     */
    async getStatus() {
        const response = await this.client.get('/api/status');
        return response.data;
    }

    /**
     * 获取会话列表，支持分页
     * @param {number} [limit=50] - 每页数量
     * @param {number} [page=1] - 页码
     * @returns {Promise<Object>} 会话摘要列表和分页信息
     */
    async getConversations(limit = 50, page = 1) {
        const response = await this.client.get('/api/conversations', {
            params: { limit, page }
        });
        return response.data;
    }

    /**
     * 按应用类型获取会话列表
     * @param {string} app - 应用标识 (deepseek/doubao/xiaoai)
     * @returns {Promise<Object>} 指定应用的会话列表
     */
    async getConversationsByApp(app) {
        const response = await this.client.get(`/api/conversations/${app}`);
        return response.data;
    }

    /**
     * 获取单个会话的完整详情，包含所有消息记录
     * @param {string} id - 会话ID
     * @returns {Promise<Object>} 完整的会话数据和消息列表
     */
    async getConversation(id) {
        const response = await this.client.get(`/api/conversations/${id}`);
        return response.data;
    }

    /**
     * 向指定AI应用发送消息
     * @param {string} app - 目标应用 (deepseek/doubao/xiaoai)
     * @param {string} message - 要发送的消息内容
     * @returns {Promise<Object>} 发送结果，包含会话ID和发送方式
     */
    async sendMessage(app, message) {
        const response = await this.client.post(`/api/send/${app}`, { message });
        return response.data;
    }

    /**
     * 删除指定会话
     * @param {string} id - 会话ID
     * @returns {Promise<Object>} 删除操作结果
     */
    async deleteConversation(id) {
        const response = await this.client.delete(`/api/conversations/${id}`);
        return response.data;
    }

    /**
     * 获取AI对话捕获的统计数据
     * @returns {Promise<Object>} 包含总会话数、消息数和各应用的统计
     */
    async getStats() {
        const response = await this.client.get('/api/stats');
        return response.data;
    }

    /**
     * 获取当前服务器配置
     * @returns {Promise<Object>} 服务器配置信息
     */
    async getConfig() {
        const response = await this.client.get('/api/config');
        return response.data;
    }

    /**
     * 更新服务器配置
     * @param {Object} config - 新的配置对象
     * @returns {Promise<Object>} 更新后的配置
     */
    async updateConfig(config) {
        const response = await this.client.post('/api/config', config);
        return response.data;
    }

    /**
     * 执行ADB命令
     * @param {string} command - ADB命令字符串
     * @returns {Promise<Object>} 命令执行结果（stdout、stderr、exitCode）
     */
    async executeAdbCommand(command) {
        const response = await this.client.post('/api/adb/command', { command });
        return response.data;
    }

    /**
     * 获取已连接的ADB设备列表
     * @returns {Promise<Object>} 设备列表
     */
    async getDevices() {
        const response = await this.client.get('/api/adb/devices');
        return response.data;
    }

    /**
     * 执行Shell命令
     * @param {string} command - Shell命令字符串
     * @returns {Promise<Object>} 命令执行结果
     */
    async executeShell(command) {
        const response = await this.client.post('/api/adb/shell', { command });
        return response.data;
    }

    /**
     * 检查服务器是否可达
     * @returns {Promise<boolean>} 服务器是否在线
     */
    async ping() {
        try {
            await this.getStatus();
            return true;
        } catch {
            return false;
        }
    }

    /**
     * 流式获取对话（轮询实现）
     * @param {string} app - 应用标识
     * @param {function} onMessage - 新消息回调
     * @param {number} [interval=2000] - 轮询间隔（毫秒）
     * @returns {Object} 包含stop方法用于停止轮询
     */
    watchConversations(app, onMessage, interval = 2000) {
        let lastTimestamp = Date.now();
        let running = true;

        const poll = async () => {
            if (!running) return;
            try {
                const result = app
                    ? await this.getConversationsByApp(app)
                    : await this.getConversations(10);

                const conversations = result.data?.conversations || [];
                conversations.forEach(c => {
                    if (c.updatedAt > lastTimestamp) {
                        onMessage(c);
                    }
                });

                if (conversations.length > 0) {
                    lastTimestamp = Math.max(...conversations.map(c => c.updatedAt));
                }
            } catch (err) {
                // 轮询错误静默处理
            }
            if (running) {
                setTimeout(poll, interval);
            }
        };

        poll();

        return {
            stop() {
                running = false;
            }
        };
    }
}

module.exports = ApiApkClient;
