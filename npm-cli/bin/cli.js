#!/usr/bin/env node

/**
 * ApiAPK CLI - AI模型I/O捕获与API代理命令行工具
 *
 * 功能概述：
 *   - 连接到Android设备上运行的ApiAPK服务
 *   - 查看、搜索和管理从DeepSeek、豆包、小爱同学捕获的对话
 *   - 通过CLI向AI应用发送消息
 *   - 启动本地API代理服务器，将手机端API转发到本地
 *   - 执行ADB命令和Shell命令
 *
 * 使用方式：
 *   apiapk connect <host>           - 连接到ApiAPK服务器
 *   apiapk status                  - 查看服务器状态
 *   apiapk list [--app]            - 列出所有会话
 *   apiapk get <id>                - 获取会话详情
 *   apiapk send <app> <message>    - 发送消息到AI应用
 *   apiapk stats                   - 查看捕获统计
 *   apiapk serve [--port]          - 启动本地API代理
 *   apiapk adb <command>           - 执行ADB命令
 *   apiapk config [--key --value]  - 查看/修改配置
 */

const { Command } = require('commander');
const chalk = require('chalk');
const ApiApkClient = require('../lib/client');
const ProxyServer = require('../lib/proxy-server');
const { displayConversations, displayConversation, displayStats, displayStatus, displayConfig } = require('../lib/display');

const program = new Command();

// 全局状态
let client = null;

function getClient() {
    if (!client) {
        const host = program.opts().host || 'localhost';
        const port = program.opts().port || 8765;
        client = new ApiApkClient(host, port);
    }
    return client;
}

program
    .name('apiapk')
    .description('ApiAPK CLI - AI模型I/O捕获与API代理工具 (DeepSeek / 豆包 / 小爱同学)')
    .version('1.0.0')
    .option('-h, --host <host>', 'ApiAPK服务器地址', 'localhost')
    .option('-p, --port <port>', 'ApiAPK服务器端口', '8765')
    .option('-k, --api-key <key>', 'API密钥（如果配置了的话）')
    .hook('preAction', (cmd) => {
        // 预处理钩子
    });

// connect 命令 - 连接到服务器
program
    .command('connect <host>')
    .description('连接到ApiAPK服务器')
    .option('-p, --port <port>', '端口', '8765')
    .action(async (host, opts) => {
        const spinner = require('ora')('正在连接到ApiAPK服务器...').start();
        try {
            client = new ApiApkClient(host, parseInt(opts.port));
            const status = await client.getStatus();
            spinner.succeed(chalk.green('✅ 连接成功!'));
            displayStatus(status);
            console.log(chalk.cyan(`\n📡 服务器地址: http://${host}:${opts.port}`));
            console.log(chalk.dim('  使用 apiapk <command> 开始操作'));
        } catch (err) {
            spinner.fail(chalk.red('❌ 连接失败'));
            console.error(chalk.dim(`  ${err.message}`));
            console.log(chalk.yellow('\n💡 提示:'));
            console.log(chalk.dim('  1. 确保手机上的ApiAPK应用已启动服务器'));
            console.log(chalk.dim('  2. 确保手机和电脑在同一WiFi网络'));
            console.log(chalk.dim('  3. 使用手机的IP地址而非localhost'));
            process.exit(1);
        }
    });

// status 命令 - 查看服务器状态
program
    .command('status')
    .description('查看ApiAPK服务器状态')
    .action(async () => {
        try {
            const status = await getClient().getStatus();
            displayStatus(status);
        } catch (err) {
            console.error(chalk.red('获取状态失败:'), err.message);
            process.exit(1);
        }
    });

// list 命令 - 列出所有会话
program
    .command('list')
    .alias('ls')
    .description('列出所有捕获的会话')
    .option('-a, --app <app>', '按应用过滤 (deepseek/doubao/xiaoai)')
    .option('-l, --limit <n>', '显示数量', '20')
    .option('--json', '以JSON格式输出')
    .action(async (opts) => {
        try {
            let conversations;
            if (opts.app) {
                const result = await getClient().getConversationsByApp(opts.app);
                conversations = result.data.conversations || result.data;
            } else {
                const result = await getClient().getConversations(parseInt(opts.limit));
                conversations = result.data.conversations || result.data;
            }

            if (opts.json) {
                console.log(JSON.stringify(conversations, null, 2));
            } else {
                displayConversations(conversations);
            }
        } catch (err) {
            console.error(chalk.red('获取会话列表失败:'), err.message);
            process.exit(1);
        }
    });

// get 命令 - 获取会话详情
program
    .command('get <id>')
    .description('获取指定会话的完整对话内容')
    .option('--json', '以JSON格式输出')
    .action(async (id, opts) => {
        try {
            const result = await getClient().getConversation(id);
            const conversation = result.data;

            if (opts.json) {
                console.log(JSON.stringify(conversation, null, 2));
            } else {
                displayConversation(conversation);
            }
        } catch (err) {
            console.error(chalk.red('获取会话失败:'), err.message);
            process.exit(1);
        }
    });

// send 命令 - 发送消息
program
    .command('send <app> <message>')
    .description('向AI应用发送消息')
    .option('--json', '以JSON格式输出')
    .action(async (app, message, opts) => {
        const validApps = ['deepseek', 'doubao', 'xiaoai'];
        if (!validApps.includes(app)) {
            console.error(chalk.red(`不支持的应用: ${app}`));
            console.log(chalk.dim(`  支持的应用: ${validApps.join(', ')}`));
            process.exit(1);
        }

        const spinner = require('ora')(`正在发送消息到 ${app}...`).start();
        try {
            const result = await getClient().sendMessage(app, message);
            spinner.succeed(chalk.green(`✅ 消息已发送到 ${app}`));

            if (opts.json) {
                console.log(JSON.stringify(result.data, null, 2));
            } else {
                const sentViaAdb = result.data?.sentViaAdb;
                console.log(chalk.dim(`  方式: ${sentViaAdb ? 'ADB自动发送' : '仅记录捕获'}`));
                console.log(chalk.dim(`  会话ID: ${result.data?.conversation?.id || 'N/A'}`));
            }
        } catch (err) {
            spinner.fail(chalk.red('❌ 发送失败'));
            console.error(chalk.dim(`  ${err.message}`));
            process.exit(1);
        }
    });

// stats 命令 - 查看统计
program
    .command('stats')
    .description('查看AI对话捕获统计')
    .option('--json', '以JSON格式输出')
    .action(async (opts) => {
        try {
            const result = await getClient().getStats();
            if (opts.json) {
                console.log(JSON.stringify(result.data, null, 2));
            } else {
                displayStats(result.data);
            }
        } catch (err) {
            console.error(chalk.red('获取统计失败:'), err.message);
            process.exit(1);
        }
    });

// serve 命令 - 启动本地API代理
program
    .command('serve')
    .description('启动本地API代理服务器，将手机端API转发到本地')
    .option('-p, --port <port>', '本地代理端口', '3000')
    .option('--proxy-host <host>', 'ApiAPK服务器地址', 'localhost')
    .option('--proxy-port <port>', 'ApiAPK服务器端口', '8765')
    .action(async (opts) => {
        const proxy = new ProxyServer(parseInt(opts.port), opts.proxyHost, parseInt(opts.proxyPort));
        await proxy.start();
    });

// adb 命令 - 执行ADB命令
program
    .command('adb <command>')
    .description('通过ApiAPK服务器执行ADB命令')
    .option('--shell', '作为shell命令执行')
    .action(async (command, opts) => {
        try {
            let result;
            if (opts.shell) {
                result = await getClient().executeShell(command);
            } else {
                result = await getClient().executeAdbCommand(command);
            }

            if (result.data?.stdout) {
                console.log(result.data.stdout);
            }
            if (result.data?.stderr) {
                console.error(chalk.yellow(result.data.stderr));
            }
        } catch (err) {
            console.error(chalk.red('ADB命令执行失败:'), err.message);
            process.exit(1);
        }
    });

// devices 命令 - 查看ADB设备
program
    .command('devices')
    .description('查看已连接的ADB设备列表')
    .action(async () => {
        try {
            const result = await getClient().getDevices();
            const devices = result.data?.devices || [];

            if (devices.length === 0) {
                console.log(chalk.yellow('没有发现设备'));
            } else {
                console.log(chalk.cyan('已连接设备:'));
                const Table = require('cli-table3');
                const table = new Table({
                    head: ['序号', '序列号', '状态'],
                    style: { head: ['cyan'] }
                });
                devices.forEach((d, i) => {
                    table.push([i + 1, d.serial || 'N/A', d.status || d.model || 'N/A']);
                });
                console.log(table.toString());
            }
        } catch (err) {
            console.error(chalk.red('获取设备列表失败:'), err.message);
            process.exit(1);
        }
    });

// config 命令 - 管理配置
program
    .command('config')
    .description('查看或修改服务器配置')
    .option('--get', '获取当前配置')
    .option('--set <json>', '设置配置 (JSON格式)')
    .action(async (opts) => {
        try {
            if (opts.set) {
                const newConfig = JSON.parse(opts.set);
                const result = await getClient().updateConfig(newConfig);
                console.log(chalk.green('✅ 配置已更新'));
                displayConfig(result.data);
            } else {
                const result = await getClient().getConfig();
                displayConfig(result.data);
            }
        } catch (err) {
            if (err instanceof SyntaxError) {
                console.error(chalk.red('JSON格式错误:'), err.message);
            } else {
                console.error(chalk.red('操作失败:'), err.message);
            }
            process.exit(1);
        }
    });

// watch 命令 - 实时监听新消息
program
    .command('watch')
    .description('实时监听新的AI对话消息')
    .option('-a, --app <app>', '只监听特定应用')
    .action(async (opts) => {
        console.log(chalk.cyan('👀 正在监听新的AI对话消息...'));
        console.log(chalk.dim('  按 Ctrl+C 停止\n'));

        let lastCount = 0;

        const poll = async () => {
            try {
                const result = await getClient().getConversations(5);
                const conversations = result.data?.conversations || [];
                const totalCount = result.data?.total || 0;

                if (totalCount > lastCount) {
                    const newOnes = conversations.filter(c =>
                        c.updatedAt > (Date.now() - 10000)
                    );

                    newOnes.forEach(c => {
                        const appNames = { deepseek: 'DeepSeek', doubao: '豆包', xiaoai: '小爱' };
                        const appName = appNames[c.app] || c.app;
                        console.log(chalk.bold(`\n[${appName}] 新消息`));
                        console.log(chalk.dim(`  会话: ${c.id}`));
                        console.log(chalk.white(`  ${c.lastMessage || '(内容)'}`));
                        console.log(chalk.dim('─'.repeat(50)));
                    });

                    lastCount = totalCount;
                }
            } catch (err) {
                // 忽略轮询错误
            }

            setTimeout(poll, 2000);
        };

        poll();

        process.on('SIGINT', () => {
            console.log(chalk.yellow('\n\n停止监听'));
            process.exit(0);
        });
    });

// 解析命令
program.parse(process.argv);

// 没有参数时显示帮助
if (!process.argv.slice(2).length) {
    program.outputHelp();
}
