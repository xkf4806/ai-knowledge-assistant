/**
 * 用 Playwright 驱动界面跑一遍完整 Demo 并录屏，产出一段可直接播放的 webm 视频（约 2 分钟）。
 *
 * 流程：知识库统计 → 知识库问答（流式 + 点开引用）→ 多轮追问 → 智能助手（工具调用 + 调用详情）
 *       → 基础对话（无知识库的对照）→ 用量与成本面板。
 *
 * 为了「录出来的视频里不能有失败」，脚本做了三道保险：
 *   ① 录前自检：先用真实接口把每个问题都跑一遍，任何一题答不出来就等待并重试，不开始录制；
 *   ② 逐题校验：录制过程中每一题都检查「有没有红色错误 / 答案是否为空 / 该有的引用与工具调用在不在」，
 *      任何一题不合格就丢弃本次录制；
 *   ③ 录后校验：确认用量里 failedCalls 为 0，不合格就整段重录（默认最多 3 次）。
 *
 * 前置：
 *   npm i playwright && npx playwright install chromium
 *   应用需已启动、配好 AI_API_KEY，并导入过示例文档（脚本不做任何 mock，走真实模型）
 *
 * 运行：
 *   node scripts/demo/record-demo.cjs
 *
 * 可选环境变量：
 *   AIKB_DEMO_BASE       应用地址，默认 http://localhost:8080
 *   AIKB_DEMO_OUT        输出目录，默认 docs/assets
 *   AIKB_DEMO_NAME       输出文件名（不含扩展名），默认 demo
 *   AIKB_DEMO_PACE       节奏倍率，默认 1（调大则各处停顿更久）
 *   AIKB_DEMO_MAX_TAKES  最多录制几次（不含录前自检），默认 3
 *   AIKB_DEMO_ROUNDS     录前自检最多重试几轮，默认 6
 *   AIKB_BROWSER         有头模式调试用：设为 1 会打开浏览器窗口
 *   AIKB_PLAYWRIGHT      已安装的 playwright 包目录
 *
 * 说明：Playwright 录的是 webm（VP9）。想转 mp4/GIF，用系统里任意一个完整版 ffmpeg：
 *   ffmpeg -i docs/assets/demo.webm -c:v libx264 -pix_fmt yuv420p docs/assets/demo.mp4
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const base = process.env.AIKB_DEMO_BASE || 'http://localhost:8080';
const outDir = process.env.AIKB_DEMO_OUT || path.join(root, 'docs', 'assets');
const outName = process.env.AIKB_DEMO_NAME || 'demo';
const pace = Number(process.env.AIKB_DEMO_PACE || 1);
const maxTakes = Number(process.env.AIKB_DEMO_MAX_TAKES || 3);
const preflightRounds = Number(process.env.AIKB_DEMO_ROUNDS || 6);
const headless = process.env.AIKB_BROWSER !== '1';
const width = 1440;
const height = 900;

let playwright;
try {
    playwright = require(process.env.AIKB_PLAYWRIGHT || 'playwright');
} catch (e) {
    console.error('找不到 playwright。先执行：npm i playwright && npx playwright install chromium');
    console.error('也可以用 AIKB_PLAYWRIGHT 指向已安装的 playwright 包目录。');
    process.exit(1);
}
const { chromium, request } = playwright;

const tmpDir = path.join(outDir, '.tmp-demo');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.round(ms * pace)));
const sleepRaw = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const hasText = (value) => typeof value === 'string' && value.trim().length > 0;

/* ---------------- 录前自检：先确认每个问题都真的答得出来 ---------------- */

async function postJson(api, url, body) {
    const response = await api.post(url, { data: body, timeout: 180000 });
    if (!response.ok()) {
        const detail = await response.text().catch(() => '');
        throw new Error(url + ' -> HTTP ' + response.status() + ' ' + detail.slice(0, 160));
    }
    return response.json();
}

async function preflightOnce() {
    const api = await request.newContext({ baseURL: base, timeout: 180000 });
    const problems = [];
    const sessionId = 'demo-preflight-' + Date.now();
    try {
        const rag1 = await postJson(api, '/api/rag/ask',
            { question: '无理由退货的期限是几天？', sessionId });
        if (!hasText(rag1.answer)) {
            problems.push('知识库问答：答案为空');
        } else if (!(rag1.citations || []).length) {
            problems.push('知识库问答：答案里没有引用编号');
        }

        const rag2 = await postJson(api, '/api/rag/ask', { question: '那运费谁承担？', sessionId });
        if (!hasText(rag2.answer)) {
            problems.push('知识库追问：答案为空');
        }

        const agent1 = await postJson(api, '/api/agent/ask', { question: '订单 A1001 能不能退？' });
        if (!hasText(agent1.answer)) {
            problems.push('智能助手：答案为空');
        } else if (!(agent1.toolsUsed || []).length) {
            problems.push('智能助手：没有调用任何工具');
        }

        const agent2 = await postJson(api, '/api/agent/ask',
            { question: '再看看 SKU-3003 的库存，买 2 件一共多少钱？' });
        if (!hasText(agent2.answer)) {
            problems.push('智能助手第二问：答案为空');
        }

        const chat = await postJson(api, '/api/chat', { message: '无理由退货的期限是几天？' });
        if (!hasText(chat.answer)) {
            problems.push('基础对话：答案为空');
        }
    } catch (error) {
        problems.push(String((error && error.message) || error));
    } finally {
        await api.dispose();
    }
    return problems;
}

async function preflight() {
    for (let round = 1; round <= preflightRounds; round++) {
        console.log('录前自检 第 ' + round + '/' + preflightRounds + ' 轮...');
        const problems = await preflightOnce();
        if (!problems.length) {
            console.log('录前自检通过：5 个问题都能正常作答。');
            return [];
        }
        console.warn('  未通过：' + problems.join('；'));
        if (round < preflightRounds) {
            console.warn('  等 15 秒后重试（大概率是网关偶发抖动）。');
            await sleepRaw(15000);
        } else {
            return problems;
        }
    }
    return ['未知'];
}

async function resetUsage() {
    const api = await request.newContext({ baseURL: base, timeout: 30000 });
    try {
        await api.delete('/api/observability/usage');
    } finally {
        await api.dispose();
    }
}

async function failedCalls() {
    const api = await request.newContext({ baseURL: base, timeout: 30000 });
    try {
        const usage = await (await api.get('/api/observability/usage')).json();
        return Number(usage.failedCalls || 0);
    } finally {
        await api.dispose();
    }
}

/* ---------------- 录制 ---------------- */

/** 等最后一条助手消息「流完」：正文不再有光标，且确实有内容。 */
async function waitAssistantDone(page, timeoutMs) {
    await page.waitForFunction(() => {
        const nodes = document.querySelectorAll('#messages-inner .msg.assistant');
        if (!nodes.length) {
            return false;
        }
        const content = nodes[nodes.length - 1].querySelector('.content');
        if (!content || content.querySelector('.caret')) {
            return false;
        }
        return content.innerText.trim().length > 5;
    }, null, { timeout: timeoutMs });
}

/** 发一个问题，并按选项校验这一轮的渲染结果；返回发现的问题列表（空数组表示这一轮没问题）。 */
async function ask(page, text, timeoutMs, options) {
    const opts = options || {};
    await page.click('#input');
    await page.fill('#input', '');
    await page.type('#input', text, { delay: Math.round(40 * pace) });
    await sleep(500);
    await page.click('#btn-send');
    await waitAssistantDone(page, timeoutMs);
    await sleep(900);

    const seen = await page.evaluate(() => {
        const nodes = document.querySelectorAll('#messages-inner .msg.assistant');
        const last = nodes[nodes.length - 1];
        const content = last ? last.querySelector('.content') : null;
        return {
            text: content ? content.innerText.trim() : '',
            danger: last ? last.querySelectorAll('.tag.danger').length : 0,
            cites: last ? last.querySelectorAll('.cite').length : 0,
            sources: last ? last.querySelectorAll('.source-item').length : 0,
            toolCalls: last ? last.querySelectorAll('.call').length : 0,
        };
    });

    const problems = [];
    if (seen.danger) {
        problems.push('「' + text + '」出现 ' + seen.danger + ' 条红色错误：' + seen.text.slice(0, 80));
    }
    if (seen.text.length < 5) {
        problems.push('「' + text + '」答案为空');
    }
    if (/没有返回内容|（无输出）/.test(seen.text)) {
        problems.push('「' + text + '」模型没有返回内容');
    }
    if (opts.expectCitations && !seen.cites) {
        problems.push('「' + text + '」答案里没有引用角标');
    }
    if (opts.expectSources && !seen.sources) {
        problems.push('「' + text + '」没有来源片段');
    }
    if (opts.expectToolCalls && !seen.toolCalls) {
        problems.push('「' + text + '」没有工具调用记录');
    }
    return problems;
}

/** 点开最后一条回答里的第一个引用角标，展开并高亮对应的来源片段。 */
async function clickLastCite(page) {
    const cite = page.locator('#messages-inner .msg.assistant .cite').last();
    if (await cite.count()) {
        await cite.click();
        await sleep(2600);
    }
}

/** 跑一次完整的录制；返回 { problems }，空数组表示这次录制干净可用。 */
async function recordOnce(take) {
    const problems = [];
    const browser = await chromium.launch({ headless });
    const context = await browser.newContext({
        viewport: { width, height },
        recordVideo: { dir: tmpDir, size: { width, height } },
    });
    const page = await context.newPage();
    const video = page.video();
    const collect = (list) => {
        for (const item of list) {
            problems.push(item);
        }
    };

    try {
        console.log('打开 ' + base);
        await page.goto(base, { waitUntil: 'domcontentloaded' });
        await page.waitForSelector('#status-text');
        await page.waitForFunction(
            () => /UP/.test(document.getElementById('status-text').textContent || ''),
            null, { timeout: 60000 });
        await sleep(1800);

        // ⓪ 开场：看一眼知识库统计（向量库、检索模式、分块策略、BM25 片段数）
        console.log('⓪ 知识库统计');
        await page.click('#btn-kb');
        await page.waitForSelector('#kb-stats .stat', { timeout: 30000 });
        await sleep(3200);
        await page.click('#kb-dialog form.dialog-head button[type="submit"]');
        await sleep(1200);

        // ① 知识库问答：流式输出 + 引用溯源
        console.log('① 知识库问答');
        await page.click('.segmented button[data-mode="rag"]');
        await sleep(800);
        collect(await ask(page, '无理由退货的期限是几天？', 240000,
            { expectCitations: true, expectSources: true }));
        await clickLastCite(page);

        // ② 多轮追问：靠会话记忆理解「那…」
        console.log('② 多轮追问');
        collect(await ask(page, '那运费谁承担？', 240000, { expectCitations: true }));
        await clickLastCite(page);

        // ③ 智能助手：工具调用 + 计划 + 调用详情
        console.log('③ 智能助手');
        await page.click('.segmented button[data-mode="agent"]');
        await sleep(1100);
        collect(await ask(page, '订单 A1001 能不能退？', 300000,
            { expectCitations: true, expectToolCalls: true }));
        const traceSummary = page.locator('#messages-inner .msg.assistant summary:has-text("调用详情")').last();
        if (await traceSummary.count()) {
            await traceSummary.click();
            await sleep(3200);
        }
        const sourcesSummary = page.locator('#messages-inner .msg.assistant summary:has-text("来源片段")').last();
        if (await sourcesSummary.count()) {
            await sourcesSummary.click();
            await sleep(3000);
        }
        collect(await ask(page, '再看看 SKU-3003 的库存，买 2 件一共多少钱？', 300000,
            { expectToolCalls: true }));
        const trace2 = page.locator('#messages-inner .msg.assistant summary:has-text("调用详情")').last();
        if (await trace2.count()) {
            await trace2.click();
            await sleep(3400);
        }

        // ④ 基础对话：同一问题，不接知识库的对照
        console.log('④ 基础对话对照');
        await page.click('.segmented button[data-mode="chat"]');
        await sleep(1000);
        collect(await ask(page, '无理由退货的期限是几天？', 180000, {}));

        // ⑤ 用量与成本
        console.log('⑤ 用量面板');
        await page.click('#btn-usage');
        await page.click('#btn-usage-refresh');
        await sleep(3200);

        await sleep(1400);
    } catch (error) {
        problems.push('录制过程出错：' + String((error && error.message) || error));
    }

    await context.close();
    await browser.close();

    const source = await video.path().catch(() => null);
    if (problems.length) {
        if (source && fs.existsSync(source)) {
            fs.rmSync(source, { force: true });
        }
        return { problems, video: null };
    }
    if (!source || !fs.existsSync(source)) {
        return { problems: ['没有拿到视频文件'], video: null };
    }
    const target = path.join(outDir, outName + '.webm');
    fs.mkdirSync(outDir, { recursive: true });
    if (fs.existsSync(target)) {
        fs.rmSync(target, { force: true });
    }
    fs.renameSync(source, target);
    return { problems: [], video: target };
}

/* ---------------- 主流程 ---------------- */

(async () => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    fs.mkdirSync(tmpDir, { recursive: true });

    const unhealthy = await preflight();
    if (unhealthy.length) {
        console.error('录前自检始终未通过，先别录：' + unhealthy.join('；'));
        console.error('多半是网关/额度问题，稍后再试；修好之后重跑本脚本即可。');
        process.exit(1);
    }

    for (let take = 1; take <= maxTakes; take++) {
        console.log('=== 第 ' + take + '/' + maxTakes + ' 次录制 ===');
        await resetUsage();
        const result = await recordOnce(take);
        if (result.problems.length) {
            console.warn('本次录制不合格，丢弃：' + result.problems.join('；'));
            continue;
        }
        const failed = await failedCalls();
        if (failed > 0) {
            console.warn('本次录制里有 ' + failed + ' 次失败的模型调用，丢弃并重录。');
            fs.rmSync(result.video, { force: true });
            continue;
        }
        const kb = Math.round(fs.statSync(result.video).size / 1024);
        console.log('录制完成且校验通过：' + result.video);
        console.log('大小：' + kb + ' KB（用量里 failedCalls = 0）');
        fs.rmSync(tmpDir, { recursive: true, force: true });
        return;
    }

    fs.rmSync(tmpDir, { recursive: true, force: true });
    console.error('连续 ' + maxTakes + ' 次录制都不合格，请稍后再试。');
    process.exit(1);
})().catch((error) => {
    console.error('录制失败：', error);
    process.exit(1);
});
