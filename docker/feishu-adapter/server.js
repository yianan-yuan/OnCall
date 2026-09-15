// Alertmanager → 飞书自定义机器人 的格式适配器
// Alertmanager 推送的是它自己的报文结构，飞书机器人只认 msg_type/content，所以中间做一次转换。
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const WEBHOOK_URL = (process.env.FEISHU_WEBHOOK_URL || '').trim();
const SECRET = (process.env.FEISHU_SECRET || '').trim();

const SEVERITY_TEXT = { critical: '严重', warning: '警告', info: '提示' };

// 飞书签名：以 "timestamp\nsecret" 为密钥、对空内容做 HMAC-SHA256 再 base64
function feishuSign(timestamp) {
    return crypto.createHmac('sha256', `${timestamp}\n${SECRET}`).update('').digest('base64');
}

function textRow(text) {
    return [{ tag: 'text', text: text }];
}

// 把 Alertmanager 报文拼成飞书 post 富文本；一次推送可能带多条告警（按 alertname + service 分组）
function buildPostBody(payload) {
    const alerts = Array.isArray(payload.alerts) ? payload.alerts : [];
    const resolved = payload.status === 'resolved';
    const firstLabels = (alerts[0] && alerts[0].labels) || {};
    const alertName = firstLabels.alertname || '未知告警';

    const title = alerts.length > 1
        ? `${resolved ? '✅' : '🚨'} ${alerts.length} 条告警${resolved ? '已恢复' : '触发'}`
        : `${resolved ? '✅' : '🚨'} ${alertName}${resolved ? ' 已恢复' : ''}`;

    const rows = [];
    alerts.slice(0, 10).forEach((alert, index) => {
        const labels = alert.labels || {};
        const annotations = alert.annotations || {};
        const severity = SEVERITY_TEXT[labels.severity] || labels.severity || '-';

        rows.push(textRow(`${alerts.length > 1 ? index + 1 + '. ' : ''}${labels.alertname || alertName}`
            + ` ｜ 服务 ${labels.service || labels.job || '-'}`
            + ` ｜ 级别 ${severity}`));
        const summary = annotations.summary || annotations.description || '';
        if (summary) {
            rows.push(textRow(`   ${summary.slice(0, 200)}`));
        }
        if (labels.instance) {
            rows.push(textRow(`   实例 ${labels.instance}`));
        }
    });

    if (alerts.length > 10) {
        rows.push(textRow(`…… 其余 ${alerts.length - 10} 条已省略`));
    }
    rows.push(textRow(`状态 ${payload.status || '-'} ｜ ${new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}`));

    return { msg_type: 'post', content: { post: { zh_cn: { title: title, content: rows } } } };
}

async function forward(body) {
    if (SECRET) {
        const timestamp = String(Math.floor(Date.now() / 1000));
        body.timestamp = timestamp;
        body.sign = feishuSign(timestamp);
    }

    if (!WEBHOOK_URL) {
        console.log('[dry-run] 未配置 FEISHU_WEBHOOK_URL，仅打印将要发送的消息:');
        console.log(JSON.stringify(body));
        return;
    }

    const response = await fetch(WEBHOOK_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    console.log(`[feishu] HTTP ${response.status} ${await response.text()}`);
}

const server = http.createServer((request, response) => {
    if (request.method === 'GET') {
        response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        response.end(JSON.stringify({ status: 'ok', configured: Boolean(WEBHOOK_URL), signed: Boolean(SECRET) }));
        return;
    }

    if (request.method !== 'POST') {
        response.writeHead(405);
        response.end();
        return;
    }

    let raw = '';
    request.on('data', (chunk) => { raw += chunk; });
    request.on('end', () => {
        // 先回 200 再转发：Alertmanager 同步等结果，回慢了会判超时并重发
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ received: true }));

        let payload;
        try {
            payload = JSON.parse(raw || '{}');
        } catch (e) {
            console.error('[feishu] 报文不是合法 JSON，已忽略:', e.message);
            return;
        }

        const alertCount = Array.isArray(payload.alerts) ? payload.alerts.length : 0;
        console.log(`[feishu] 收到告警推送: 条数=${alertCount}, 分组=${payload.groupKey || '-'}, 状态=${payload.status || '-'}`);

        forward(buildPostBody(payload)).catch((e) => console.error('[feishu] 转发失败:', e.message));
    });
});

server.listen(PORT, () => {
    console.log(`feishu-adapter listening on ${PORT}（configured=${Boolean(WEBHOOK_URL)}）`);
});
