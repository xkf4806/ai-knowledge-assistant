/**
 * 把 docs/diagrams/*.mmd 里的 mermaid 架构图渲染成 PNG，供 README / 文档直接展示。
 *
 * 约定：markdown 里只放渲染后的图片，mermaid 源码单独放在 docs/diagrams/ 下。
 * 改图请改 .mmd，然后重跑本脚本刷新 PNG，图和源码不会脱节。
 *
 * 前置（装到任意目录都行，不必装进仓库）：
 *   npm i playwright mermaid && npx playwright install chromium
 *   国内网络建议加 --registry=https://registry.npmmirror.com
 *
 * 运行：
 *   node scripts/demo/render-diagrams.cjs
 *
 * 可选环境变量：
 *   AIKB_PLAYWRIGHT    已安装的 playwright 包目录（默认按常规 node 解析）
 *   AIKB_MERMAID_JS    本地 mermaid UMD 文件路径（默认 node_modules/mermaid/dist/mermaid.min.js；
 *                      找不到时退回 unpkg CDN）
 *   AIKB_DIAGRAM_SCALE 截图倍率，默认 2（2 倍分辨率，放大后不糊）
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const outDir = path.join(root, 'docs', 'assets');
const scale = Number(process.env.AIKB_DIAGRAM_SCALE || 2);
const mermaidJs = process.env.AIKB_MERMAID_JS
    || path.join(root, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js');
const mermaidCdn = 'https://unpkg.com/mermaid@11.4.1/dist/mermaid.esm.min.mjs';

let chromium;
try {
    ({ chromium } = require(process.env.AIKB_PLAYWRIGHT || 'playwright'));
} catch (e) {
    console.error('找不到 playwright。先执行：npm i playwright && npx playwright install chromium');
    console.error('也可以用 AIKB_PLAYWRIGHT 指向已安装的 playwright 包目录。');
    process.exit(1);
}

/** mermaid 源码里的 <br/> 等标签要转义，浏览器解析 <pre> 时才能原样交回 textContent。 */
function escapeHtml(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const MERMAID_CONFIG = {
    startOnLoad: false,
    theme: 'base',
    themeVariables: {
        primaryColor: '#e6f4f1',
        primaryBorderColor: '#0f766e',
        primaryTextColor: '#0f172a',
        lineColor: '#94a3b8',
        secondaryColor: '#f1f5f9',
        tertiaryColor: '#f8fafc',
        fontFamily: '"Microsoft YaHei", "Segoe UI", system-ui, sans-serif',
        fontSize: '15px',
    },
    flowchart: { htmlLabels: true, curve: 'basis', useMaxWidth: false, nodeSpacing: 45, rankSpacing: 60 },
};

function pageHtml(mermaidSource) {
    return [
        '<!doctype html><html><head><meta charset="utf-8"><style>',
        '  body { margin: 0; padding: 28px; background: #ffffff; }',
        '  .diagram { display: inline-block; }',
        '</style></head><body>',
        '  <div class="diagram"><pre class="mermaid">' + escapeHtml(mermaidSource) + '</pre></div>',
        '</body></html>',
    ].join('\n');
}

const targets = [
    { name: 'architecture-overview', source: path.join('docs', 'diagrams', 'architecture-overview.mmd') },
    { name: 'architecture-layers', source: path.join('docs', 'diagrams', 'architecture-layers.mmd') },
    { name: 'architecture-deploy', source: path.join('docs', 'diagrams', 'architecture-deploy.mmd') },
];

(async () => {
    fs.mkdirSync(outDir, { recursive: true });
    const local = fs.existsSync(mermaidJs);
    console.log('mermaid: ' + (local ? mermaidJs : 'CDN ' + mermaidCdn));
    const browser = await chromium.launch();
    for (const target of targets) {
        const file = path.join(root, target.source);
        if (!fs.existsSync(file)) {
            console.error('跳过 ' + target.name + '：找不到 ' + target.source);
            continue;
        }
        const source = fs.readFileSync(file, 'utf8').trim();
        const context = await browser.newContext({
            viewport: { width: 1800, height: 1400 },
            deviceScaleFactor: scale,
        });
        const page = await context.newPage();
        page.on('pageerror', (error) => console.error('  pageerror:', error.message));
        await page.setContent(pageHtml(source), { waitUntil: 'domcontentloaded' });

        if (local) {
            await page.addScriptTag({ path: mermaidJs });
        } else {
            await page.addScriptTag({ url: mermaidCdn, type: 'module' });
        }
        const error = await page.evaluate(async (config) => {
            try {
                window.mermaid.initialize(config);
                await window.mermaid.run({ querySelector: '.mermaid' });
                return null;
            } catch (e) {
                return String((e && e.message) || e);
            }
        }, MERMAID_CONFIG);
        if (error) {
            console.error('  渲染报错：' + error);
        }

        const outFile = path.join(outDir, target.name + '.png');
        await (await page.$('.diagram')).screenshot({ path: outFile });
        const kb = Math.round(fs.statSync(outFile).size / 1024);
        console.log(target.name + '.png  ' + kb + ' KB');
        await context.close();
    }
    await browser.close();
})().catch((error) => {
    console.error('渲染失败：', error);
    process.exit(1);
});
