/**
 * 终端显示工具 - 格式化输出ApiAPK数据到终端界面。
 * 使用chalk实现彩色输出，cli-table3实现表格展示。
 * 为所有CLI命令提供统一、美观的展示格式。
 */

const chalk = require('chalk');
const Table = require('cli-table3');

/**
 * 显示服务器状态信息
 */
function displayStatus(statusData) {
    const data = statusData.data || statusData;

    console.log('');
    console.log(chalk.bold.cyan('═══ ApiAPK 服务器状态 ═══'));
    console.log('');

    const rows = [
        ['版本', data.version || 'N/A'],
        ['捕获服务', data.captureServiceActive ? chalk.green('🟢 运行中') : chalk.red('🔴 未启用')],
        ['会话数量', String(data.conversationsCount || 0)],
        ['ADB可用', data.adbAvailable ? chalk.green('是') : chalk.red('否')],
    ];

    const table = new Table({
        style: { head: ['cyan'], border: ['gray'] },
        colWidths: [20, 40],
    });

    rows.forEach(([key, value]) => table.push([chalk.bold(key), value]));
    console.log(table.toString());

    if (data.endpoints && data.endpoints.length > 0) {
        console.log(chalk.dim('\n可用端点:'));
        data.endpoints.forEach(ep => {
            const method = ep.split(' ')[0];
            const path = ep.split(' ').slice(1).join(' ');
            console.log(chalk.dim(`  ${chalk.green(method.padEnd(6))} ${path}`));
        });
    }
    console.log('');
}

/**
 * 显示会话列表
 */
function displayConversations(conversations) {
    if (!conversations || conversations.length === 0) {
        console.log(chalk.yellow('没有捕获到任何会话'));
        console.log(chalk.dim('  提示: 请确保ApiAPK无障碍服务已启用，并打开目标AI应用'));
        return;
    }

    const appColors = {
        deepseek: chalk.hex('#4FC3F7'),
        doubao: chalk.hex('#FF7043'),
        xiaoai: chalk.hex('#66BB6A'),
        custom: chalk.hex('#AB47BC'),
    };

    console.log(chalk.bold.cyan(`\n📋 会话列表 (${conversations.length} 条)\n`));

    const table = new Table({
        head: ['#', '应用', '消息数', '最后消息', '更新时间'],
        style: { head: ['cyan'], border: ['gray'] },
        colWidths: [4, 12, 8, 36, 20],
        wordWrap: true,
    });

    conversations.forEach((c, i) => {
        const colorFn = appColors[c.app] || chalk.white;
        const time = formatTime(c.updatedAt);
        const msg = (c.lastMessage || '(空)').substring(0, 50);
        table.push([
            String(i + 1),
            colorFn(c.appDisplayName || c.app),
            String(c.messageCount || 0),
            msg,
            time,
        ]);
    });

    console.log(table.toString());
    console.log(chalk.dim('\n使用 apiapk get <id> 查看完整对话'));
}

/**
 * 显示单个会话详情
 */
function displayConversation(conversation) {
    if (!conversation) {
        console.log(chalk.yellow('会话不存在'));
        return;
    }

    const appNames = {
        deepseek: 'DeepSeek',
        doubao: '豆包',
        xiaoai: '小爱同学',
        custom: 'Custom',
    };

    console.log('');
    console.log(chalk.bold.cyan('═══ 会话详情 ═══'));
    console.log('');
    console.log(chalk.dim(`  ID: ${conversation.id}`));
    console.log(chalk.dim(`  应用: ${appNames[conversation.app] || conversation.app}`));
    console.log(chalk.dim(`  消息: ${conversation.messages?.length || 0} 条`));
    console.log(chalk.dim(`  开始: ${formatTime(conversation.startedAt)}`));
    console.log('');
    console.log(chalk.cyan('─'.repeat(60)));

    if (conversation.messages && conversation.messages.length > 0) {
        conversation.messages.forEach((msg) => {
            const isUser = msg.role === 'user';
            const prefix = isUser ? chalk.bold.blue('👤 用户') : chalk.bold.green('🤖 助手');
            const time = formatTime(msg.timestamp);

            console.log(`\n${prefix} ${chalk.dim(`[${time}]`)}`);
            console.log(chalk.white(msg.content || '(空)'));
        });
    } else {
        console.log(chalk.dim('\n  (无消息)'));
    }

    console.log('\n' + chalk.cyan('─'.repeat(60)));
    console.log('');
}

/**
 * 显示统计数据
 */
function displayStats(statsData) {
    const data = statsData || {};

    console.log('');
    console.log(chalk.bold.cyan('═══ 捕获统计 ═══'));
    console.log('');
    console.log(chalk.bold(`  总会话数: ${chalk.cyan(String(data.totalConversations || 0))}`));
    console.log(chalk.bold(`  总消息数: ${chalk.cyan(String(data.totalMessages || 0))}`));
    console.log('');

    if (data.byApp) {
        console.log(chalk.cyan('  各应用统计:'));
        console.log('');

        const table = new Table({
            head: ['应用', '会话数', '消息数'],
            style: { head: ['cyan'], border: ['gray'] },
        });

        Object.entries(data.byApp).forEach(([app, stats]) => {
            const appNames = {
                deepseek: '🔵 DeepSeek',
                doubao: '🟠 豆包',
                xiaoai: '🟢 小爱同学',
                custom: '🟣 自定义',
            };
            table.push([
                appNames[app] || app,
                String(stats.conversations || 0),
                String(stats.messages || 0),
            ]);
        });

        console.log(table.toString());
    }
    console.log('');
}

/**
 * 显示配置信息
 */
function displayConfig(config) {
    if (!config) {
        console.log(chalk.yellow('无法获取配置'));
        return;
    }

    console.log('');
    console.log(chalk.bold.cyan('═══ 服务器配置 ═══'));
    console.log('');

    const table = new Table({
        style: { head: ['cyan'], border: ['gray'] },
        colWidths: [20, 30],
    });

    const entries = [
        ['服务器地址', config.serverHost || '0.0.0.0'],
        ['端口', String(config.serverPort || 8765)],
        ['API密钥', config.apiKey ? '****' + config.apiKey.slice(-4) : '(未设置)'],
        ['CORS', config.enableCors ? '✅ 已启用' : '❌ 已禁用'],
        ['最大会话数', String(config.maxConversations || 1000)],
        ['AI捕获', config.captureEnabled ? '✅ 已启用' : '❌ 已禁用'],
        ['自动启动', config.autoStart ? '✅ 是' : '❌ 否'],
        ['日志级别', config.logLevel || 'INFO'],
    ];

    entries.forEach(([key, value]) => table.push([chalk.bold(key), value]));
    console.log(table.toString());
    console.log('');
}

/**
 * 格式化时间戳为可读字符串
 */
function formatTime(timestamp) {
    if (!timestamp) return 'N/A';
    const date = new Date(timestamp);
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

module.exports = {
    displayConversations,
    displayConversation,
    displayStats,
    displayStatus,
    displayConfig,
};
