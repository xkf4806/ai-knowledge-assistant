/* 企业知识库智能助手 - 前端。
 * 单文件、零构建：直接读 /api/* 的 JSON 与 SSE 流。
 * 对话记录按模式保存在 localStorage，刷新后能接上。 */

(function () {
    'use strict';

    const MODES = {
        rag: {
            label: '知识库问答',
            endpoint: '/api/rag/ask/stream',
            clearEndpoint: (id) => `/api/rag/sessions/${encodeURIComponent(id)}`,
            body: (text, ui) => ({
                question: text,
                sessionId: state.sessions.rag,
                retrievalMode: ui.retrievalMode || undefined,
                source: ui.source || undefined
            }),
            examples: [
                '无理由退货的期限是几天？运费谁承担？',
                '员工手册里年假怎么算？',
                'VPN 账号有效期是多少天？'
            ]
        },
        agent: {
            label: '智能助手',
            endpoint: '/api/agent/ask/stream',
            clearEndpoint: (id) => `/api/agent/sessions/${encodeURIComponent(id)}`,
            body: (text) => ({question: text, sessionId: state.sessions.agent}),
            examples: [
                '订单 A1001 能不能退？',
                'SKU-2002 还有多少库存？',
                '买 2 件 SKU-3003 一共多少钱？'
            ]
        },
        chat: {
            label: '基础对话',
            endpoint: '/api/chat/stream',
            clearEndpoint: (id) => `/api/chat/sessions/${encodeURIComponent(id)}`,
            body: (text) => ({message: text, sessionId: state.sessions.chat}),
            examples: ['用一句话说明什么是 RAG', '帮我写一段项目介绍']
        }
    };

    const STORAGE_KEY = 'aikb.chat.v1';
    const MAX_PERSISTED_MESSAGES = 30;
    const MAX_PERSISTED_TEXT = 20000;
    /** 上传/导入会调用 embedding 接口，网络慢时确实要等；但必须有个上限，不能无限转圈。 */
    const KB_TIMEOUT_MS = 180000;

    const state = {
        mode: 'rag',
        sessions: {rag: null, agent: null, chat: null},
        messages: {rag: [], agent: [], chat: []},
        streaming: false,
        controller: null,
        usage: null,
        kbStatus: null,
        kbAbort: null
    };

    const el = {
        messages: document.getElementById('messages'),
        messagesInner: document.getElementById('messages-inner'),
        composer: document.getElementById('composer'),
        input: document.getElementById('input'),
        send: document.getElementById('btn-send'),
        stop: document.getElementById('btn-stop'),
        layout: document.getElementById('layout'),
        retrievalMode: document.getElementById('retrieval-mode'),
        sourceFilter: document.getElementById('source-filter'),
        fieldRetrieval: document.getElementById('field-retrieval'),
        sourceField: document.getElementById('field-source'),
        statusChip: document.getElementById('status-chip'),
        statusText: document.getElementById('status-text'),
        vectorText: document.getElementById('vector-text'),
        panelBody: document.getElementById('panel-body'),
        kbDialog: document.getElementById('kb-dialog'),
        kbStats: document.getElementById('kb-stats'),
        kbResult: document.getElementById('kb-result'),
        uploadFile: document.getElementById('upload-file'),
        uploadStrategy: document.getElementById('upload-strategy'),
        kbIngest: document.getElementById('btn-ingest'),
        kbUpload: document.getElementById('btn-upload'),
        kbCancel: document.getElementById('btn-kb-cancel')
    };

    /* ---------------- helpers ---------------- */

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function inline(text) {
        return text
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/`([^`]+)`/g, '<code>$1</code>')
            .replace(/\[(\d{1,2})]/g, (match, n) => `<button type="button" class="cite" data-cite="${n}">${n}</button>`);
    }

    /** 轻量 Markdown：只处理模型最常用的段落、列表、加粗、行内代码与引用角标。 */
    function markdown(text) {
        if (!text) {
            return '';
        }
        const out = [];
        let list = null;
        const flush = () => {
            if (list) {
                out.push(`<${list.tag}>${list.items.map((item) => `<li>${item}</li>`).join('')}</${list.tag}>`);
                list = null;
            }
        };
        escapeHtml(text).split(/\r?\n/).forEach((raw) => {
            const line = raw.replace(/\s+$/, '');
            const bullet = line.match(/^\s*[-*]\s+(.*)$/);
            const ordered = line.match(/^\s*\d+[.)]\s+(.*)$/);
            if (bullet || ordered) {
                const tag = bullet ? 'ul' : 'ol';
                if (!list || list.tag !== tag) {
                    flush();
                    list = {tag, items: []};
                }
                list.items.push(inline((bullet || ordered)[1]));
                return;
            }
            flush();
            if (line.trim()) {
                out.push(`<p>${inline(line)}</p>`);
            }
        });
        flush();
        return out.join('');
    }

    function formatInt(value) {
        return Number(value || 0).toLocaleString('en-US');
    }

    function currencySymbol(currency) {
        return currency === 'CNY' ? '¥' : (currency === 'USD' ? '$' : `${currency || ''} `);
    }

    function formatCost(value, currency) {
        const number = Number(value || 0);
        if (number === 0) {
            return `${currencySymbol(currency)}0`;
        }
        if (number < 0.01) {
            return `${currencySymbol(currency)}${number.toFixed(4)}`;
        }
        return `${currencySymbol(currency)}${number.toFixed(3)}`;
    }

    function formatDuration(ms) {
        const value = Number(ms || 0);
        return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`;
    }

    function clockTime(instant) {
        const date = instant ? new Date(instant) : new Date();
        return date.toLocaleTimeString('zh-CN', {hour12: false});
    }

    function icons() {
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
    }

    function scrollToEnd() {
        el.messages.scrollTop = el.messages.scrollHeight;
    }

    function num(value) {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : 0;
    }

    function trim(text, max) {
        const value = String(text || '');
        return value.length <= max ? value : `${value.slice(0, max)}...`;
    }

    /* ---------------- 对话记录的持久化 ---------------- */

    function restore() {
        let saved = null;
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            saved = raw ? JSON.parse(raw) : null;
        } catch (error) {
            saved = null;
        }
        if (!saved || typeof saved !== 'object') {
            return;
        }
        Object.keys(MODES).forEach((mode) => {
            const sessionId = saved.sessions && saved.sessions[mode];
            if (typeof sessionId === 'string' && sessionId) {
                state.sessions[mode] = sessionId;
            }
            const messages = saved.messages && saved.messages[mode];
            if (Array.isArray(messages)) {
                state.messages[mode] = messages.filter(isValidRecord);
            }
        });
        if (saved.mode && MODES[saved.mode]) {
            state.mode = saved.mode;
        }
    }

    function isValidRecord(record) {
        return record && (record.role === 'user' || record.role === 'assistant') && typeof record.text === 'string';
    }

    function persist() {
        try {
            const messages = {};
            Object.keys(MODES).forEach((mode) => {
                messages[mode] = state.messages[mode]
                    .slice(-MAX_PERSISTED_MESSAGES)
                    .map((record) => ({
                        role: record.role,
                        text: record.text.length > MAX_PERSISTED_TEXT
                            ? record.text.slice(0, MAX_PERSISTED_TEXT)
                            : record.text,
                        sources: record.sources || [],
                        done: record.done || null
                    }));
            });
            localStorage.setItem(STORAGE_KEY, JSON.stringify({
                mode: state.mode,
                sessions: state.sessions,
                messages
            }));
        } catch (error) {
            /* 隐私模式或配额满：记不住就算了，不影响对话本身 */
        }
    }

    /* ---------------- messages ---------------- */

    function renderEmptyState() {
        el.messagesInner.innerHTML = '';
        const block = document.createElement('div');
        block.className = 'empty';
        const mode = MODES[state.mode];
        block.innerHTML = `
            <h1>${escapeHtml(mode.label)}</h1>
            <div class="examples">
                ${mode.examples.map((item) => `<button type="button" class="example">${escapeHtml(item)}</button>`).join('')}
            </div>`;
        block.querySelectorAll('.example').forEach((button) => {
            button.addEventListener('click', () => {
                el.input.value = button.textContent;
                autoGrow();
                el.input.focus();
            });
        });
        el.messagesInner.appendChild(block);
        icons();
    }

    /** 每个模式各留一份自己的对话记录，切换模式或刷新后都按这份记录重新渲染。 */
    function mountTranscript() {
        const records = state.messages[state.mode];
        if (!records.length) {
            renderEmptyState();
            return;
        }
        el.messagesInner.innerHTML = '';
        records.forEach((record) => {
            if (record.role === 'user') {
                el.messagesInner.appendChild(userNode(record.text));
                return;
            }
            const message = assistantNode();
            el.messagesInner.appendChild(message.node);
            message.setText(record.text);
            if (record.done) {
                finishAssistant(message, record.done, record.sources || [], state.mode);
            } else {
                message.finish();
            }
        });
        icons();
        scrollToEnd();
    }

    function userNode(text) {
        const node = document.createElement('article');
        node.className = 'msg user';
        node.innerHTML = `<div class="bubble"><div class="content">${markdown(text)}</div></div>`;
        return node;
    }

    function pushUser(text) {
        state.messages[state.mode].push({role: 'user', text});
        if (state.messages[state.mode].length === 1) {
            el.messagesInner.innerHTML = '';
        }
        el.messagesInner.appendChild(userNode(text));
        icons();
        scrollToEnd();
    }

    function pushAssistant() {
        const record = {role: 'assistant', text: '', sources: [], done: null};
        state.messages[state.mode].push(record);
        if (state.messages[state.mode].length === 1) {
            el.messagesInner.innerHTML = '';
        }
        const message = assistantNode();
        el.messagesInner.appendChild(message.node);
        icons();
        scrollToEnd();
        return {record, message};
    }

    function assistantNode() {
        const node = document.createElement('article');
        node.className = 'msg assistant';
        node.innerHTML = `
            <span class="avatar"><i data-lucide="sparkles"></i></span>
            <div class="bubble">
                <div class="content"><span class="caret"></span></div>
                <div class="msg-meta" hidden></div>
                <div class="extras"></div>
            </div>`;
        const contentEl = node.querySelector('.content');
        const metaEl = node.querySelector('.msg-meta');
        const extrasEl = node.querySelector('.extras');
        let text = '';

        node.addEventListener('click', (event) => {
            const cite = event.target.closest('.cite');
            if (!cite) {
                return;
            }
            const target = extrasEl.querySelector(`.source-item[data-index="${cite.dataset.cite}"]`);
            if (target) {
                const details = target.closest('details');
                if (details) {
                    details.open = true;
                }
                target.scrollIntoView({block: 'center', behavior: 'smooth'});
                target.classList.add('highlight');
                setTimeout(() => target.classList.remove('highlight'), 1400);
            }
        });

        return {
            node,
            get text() {
                return text;
            },
            setText(value) {
                text = value || '';
                contentEl.innerHTML = markdown(text);
            },
            append(chunk) {
                text += chunk;
                contentEl.innerHTML = `${markdown(text)}<span class="caret"></span>`;
                scrollToEnd();
            },
            setMeta(chips) {
                metaEl.innerHTML = chips.join('');
                metaEl.hidden = chips.length === 0;
                icons();
            },
            setExtras(html) {
                extrasEl.innerHTML = html;
                icons();
            },
            finish() {
                contentEl.innerHTML = markdown(text)
                    || '<p class="tag warn">模型这次没有返回内容，可以再问一次。</p>';
                scrollToEnd();
            },
            fail(message) {
                contentEl.innerHTML = `<p class="tag danger">${escapeHtml(message)}</p>`;
                scrollToEnd();
            }
        };
    }

    function usageChips(usage, cost, durationMs, currency) {
        const chips = [];
        if (usage && num(usage.totalTokens) > 0) {
            chips.push(`<span class="tag">${formatInt(usage.totalTokens)} tokens</span>`);
        }
        if (cost != null) {
            chips.push(`<span class="tag">${formatCost(cost, currency)}</span>`);
        }
        if (durationMs != null) {
            chips.push(`<span class="tag">${formatDuration(durationMs)}</span>`);
        }
        return chips;
    }

    function sourcesBlock(sources) {
        if (!sources || !sources.length) {
            return '';
        }
        const items = sources.map((source) => {
            const index = String(source.label || '').replace(/\D/g, '');
            return `
                <div class="source-item" data-index="${escapeHtml(index)}">
                    <div class="source-head">
                        <span class="label">${escapeHtml(source.label || '')}</span>
                        <span class="location" title="${escapeHtml(source.location || '')}">${escapeHtml(source.location || source.source || '')}</span>
                        <span class="tag">${escapeHtml(source.retrievalMode || '')}</span>
                    </div>
                    <div class="source-snippet">${escapeHtml(source.snippet || '')}</div>
                </div>`;
        }).join('');
        return `
            <details class="block">
                <summary><i data-lucide="file-text"></i>来源片段（${sources.length}）</summary>
                <div class="block-body">${items}</div>
            </details>`;
    }

    function traceBlock(done) {
        const plan = done.plan;
        const calls = done.toolCalls || [];
        const sections = [];

        if (plan && plan.planned && (plan.steps || []).length) {
            const steps = plan.steps.map((step) => `
                <div class="step">
                    <span class="idx">${escapeHtml(step.order)}</span>
                    <span>
                        ${escapeHtml(step.action)}
                        ${step.tool ? `<span class="tool">${escapeHtml(step.tool)}</span>` : ''}
                    </span>
                </div>`).join('');
            sections.push(`<div><h3>执行计划</h3><div class="steps">${steps}</div></div>`);
        }

        if (calls.length) {
            const items = calls.map((call) => {
                const status = String(call.status || '').toLowerCase();
                const tagClass = status === 'ok' ? (call.attempts > 1 ? 'warn' : 'ok')
                    : (status === 'blocked' ? 'warn' : 'danger');
                const tags = [
                    `<span class="tag ${tagClass}">${escapeHtml(call.status || '')}</span>`,
                    `<span class="tag">${formatDuration(call.durationMs)}</span>`
                ];
                if (num(call.attempts) > 1) {
                    tags.push(`<span class="tag warn">重试 ${escapeHtml(call.attempts)} 次</span>`);
                }
                return `
                    <div class="call">
                        <div class="call-head">
                            <span class="name">${escapeHtml(call.name || '')}</span>
                            ${tags.join('')}
                        </div>
                        <pre>参数 ${escapeHtml(call.arguments || '')}</pre>
                        <pre>${escapeHtml(trim(call.result || '', 400))}</pre>
                        ${call.error ? `<pre class="danger-text">错误 ${escapeHtml(call.error)}</pre>` : ''}
                    </div>`;
            }).join('');
            sections.push(`<div><h3>工具调用（${calls.length}）</h3>${items}</div>`);
        }

        if (!sections.length) {
            return '';
        }
        return `
            <details class="block">
                <summary><i data-lucide="list-tree"></i>调用详情</summary>
                <div class="block-body">${sections.join('')}</div>
            </details>`;
    }

    /* ---------------- streaming ---------------- */

    async function send(text) {
        const mode = state.mode;
        const config = MODES[mode];
        if (state.streaming) {
            return;
        }
        pushUser(text);
        const {record, message} = pushAssistant();
        persist();
        setStreaming(true);

        const controller = new AbortController();
        state.controller = controller;
        let metaSources = [];

        try {
            const response = await fetch(config.endpoint, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(config.body(text, currentUiOptions())),
                signal: controller.signal
            });
            if (!response.ok || !response.body) {
                throw new Error(await readError(response));
            }
            await readStream(response.body, (event) => {
                if (event.name === 'meta') {
                    metaSources = event.data.sources || [];
                    if (event.data.sessionId) {
                        state.sessions[mode] = event.data.sessionId;
                    }
                    const chips = [];
                    if (event.data.retrievalMode) {
                        chips.push(`<span class="tag accent">${escapeHtml(event.data.retrievalMode)}</span>`);
                    }
                    if (event.data.plan && event.data.plan.planned) {
                        chips.push(`<span class="tag accent">计划 ${event.data.plan.steps.length} 步</span>`);
                    }
                    message.setMeta(chips);
                    return;
                }
                if (event.name === 'delta') {
                    record.text += event.data.text || '';
                    message.append(event.data.text || '');
                    return;
                }
                if (event.name === 'done') {
                    record.done = event.data;
                    record.sources = metaSources;
                    finishAssistant(message, event.data, metaSources, mode);
                    return;
                }
                if (event.name === 'error') {
                    message.fail(event.data.message || '生成失败');
                }
            });
        } catch (error) {
            if (error.name === 'AbortError') {
                message.fail('已停止生成');
            } else {
                message.fail(error.message || '请求失败');
            }
        } finally {
            setStreaming(false);
            state.controller = null;
            persist();
            refreshUsage();
        }
    }

    function finishAssistant(message, done, metaSources, mode) {
        message.finish();
        const chips = usageChips(done.usage, done.estimatedCost, done.durationMs,
            (state.usage && state.usage.currency) || 'CNY');
        if (done.toolsUsed && done.toolsUsed.length) {
            chips.push(`<span class="tag accent">${done.toolsUsed.map(escapeHtml).join(' / ')}</span>`);
        }
        if (done.degraded) {
            chips.push('<span class="tag warn">部分数据降级</span>');
        }
        message.setMeta(chips);
        const extras = (mode === 'agent' ? traceBlock(done) : '') + sourcesBlock(metaSources);
        message.setExtras(extras);
    }

    /** fetch 的流式响应体不能直接用 EventSource（要 POST），所以自己按 SSE 格式切块解析。 */
    async function readStream(body, onEvent) {
        const reader = body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        for (;;) {
            const {value, done} = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, {stream: true});
            let boundary = buffer.indexOf('\n\n');
            while (boundary >= 0) {
                const raw = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                const event = parseEvent(raw);
                if (event) {
                    onEvent(event);
                }
                boundary = buffer.indexOf('\n\n');
            }
        }
        const tail = parseEvent(buffer);
        if (tail) {
            onEvent(tail);
        }
    }

    function parseEvent(raw) {
        if (!raw || !raw.trim()) {
            return null;
        }
        let name = 'message';
        const data = [];
        raw.split('\n').forEach((line) => {
            if (line.startsWith('event:')) {
                name = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                data.push(line.slice(5).replace(/^ /, ''));
            }
        });
        if (!data.length) {
            return null;
        }
        try {
            return {name, data: JSON.parse(data.join('\n'))};
        } catch (error) {
            return null;
        }
    }

    async function readError(response) {
        try {
            const payload = await response.json();
            return payload.error || payload.message || `HTTP ${response.status}`;
        } catch (error) {
            return `HTTP ${response.status}`;
        }
    }

    function setStreaming(active) {
        state.streaming = active;
        el.send.hidden = active;
        el.stop.hidden = !active;
        el.input.disabled = active;
        el.retrievalMode.disabled = active;
        el.sourceFilter.disabled = active;
    }

    function currentUiOptions() {
        return {
            retrievalMode: el.retrievalMode.value,
            source: el.sourceFilter.value.trim()
        };
    }

    function autoGrow() {
        el.input.style.height = 'auto';
        el.input.style.height = `${Math.min(el.input.scrollHeight, 160)}px`;
    }

    function setMode(mode) {
        state.mode = mode;
        document.querySelectorAll('.segmented button').forEach((button) => {
            button.setAttribute('aria-selected', String(button.dataset.mode === mode));
        });
        el.fieldRetrieval.hidden = mode !== 'rag';
        el.sourceField.hidden = mode !== 'rag';
        el.input.placeholder = {
            rag: '问点什么，例如：无理由退货的期限是几天？',
            agent: '问点什么，例如：订单 A1001 能不能退？',
            chat: '和模型随便聊聊'
        }[mode];
        mountTranscript();
        persist();
        refreshUsage();
    }

    /* ---------------- usage panel ---------------- */

    async function refreshUsage() {
        try {
            const response = await fetch('/api/observability/usage');
            if (!response.ok) {
                return;
            }
            state.usage = await response.json();
            renderUsage();
        } catch (error) {
            /* 面板不是主流程，失败就保持上一次的内容 */
        }
    }

    function renderUsage() {
        const usage = state.usage;
        if (!usage) {
            return;
        }
        const currency = usage.currency || 'CNY';
        const stats = `
            <div class="stat-grid">
                <div class="stat"><div class="k"><i data-lucide="hash"></i>调用次数</div>
                    <div class="v">${formatInt(usage.calls)}</div></div>
                <div class="stat"><div class="k"><i data-lucide="coins"></i>估算费用</div>
                    <div class="v">${formatCost(usage.estimatedCost, currency)}</div></div>
                <div class="stat"><div class="k"><i data-lucide="binary"></i>总 tokens</div>
                    <div class="v">${formatInt(usage.totalTokens)}</div></div>
                <div class="stat"><div class="k"><i data-lucide="clock"></i>平均耗时</div>
                    <div class="v">${formatDuration(usage.avgDurationMs)}</div></div>
            </div>`;

        const breakdown = (title, map) => {
            const entries = Object.entries(map || {});
            if (!entries.length) {
                return '';
            }
            const max = Math.max(...entries.map(([, value]) => num(value.totalTokens)), 1);
            const rows = entries.map(([key, value]) => `
                <div class="bar-row">
                    <span class="label" title="${escapeHtml(key)}">${escapeHtml(key)}</span>
                    <span class="bar"><span style="width:${Math.max(2, num(value.totalTokens) / max * 100)}%"></span></span>
                    <span class="value">${formatInt(value.totalTokens)}</span>
                </div>`).join('');
            return `<div class="panel-section"><h3>${escapeHtml(title)}</h3>${rows}</div>`;
        };

        const calls = usage.recentCalls || [];
        const table = calls.length ? `
            <div class="panel-section">
                <h3>最近调用</h3>
                <table class="call-table">
                    <thead><tr><th>时间</th><th>入口</th><th>tokens</th><th>耗时</th></tr></thead>
                    <tbody>
                    ${calls.map((call) => `
                        <tr class="${call.success ? '' : 'failed'}">
                            <td>${clockTime(call.time)}</td>
                            <td>${escapeHtml(call.source)}</td>
                            <td>${call.success ? formatInt(call.totalTokens) : '失败'}</td>
                            <td>${formatDuration(call.durationMs)}</td>
                        </tr>`).join('')}
                    </tbody>
                </table>
            </div>` : '<div class="panel-empty">还没有调用记录。</div>';

        const pricing = `<div class="panel-section"><h3>计价口径</h3>
            <div class="panel-empty">输入 ${escapeHtml(String(usage.promptPricePer1M))} / 百万 tokens ·
            输出 ${escapeHtml(String(usage.completionPricePer1M))} / 百万 tokens（${escapeHtml(currency)}）</div></div>`;

        el.panelBody.innerHTML = stats + breakdown('按入口', usage.bySource) + breakdown('按模型', usage.byModel) + table + pricing;
        icons();
    }

    function setPanelVisible(visible) {
        el.layout.classList.toggle('panel-hidden', !visible);
        if (visible) {
            refreshUsage();
        }
    }

    /* ---------------- knowledge base ---------------- */

    async function loadKbStatus() {
        try {
            const [status, tools] = await Promise.all([
                fetch('/api/rag/status').then((r) => r.ok ? r.json() : null),
                fetch('/api/agent/tools').then((r) => r.ok ? r.json() : null)
            ]);
            state.kbStatus = status;
            if (status) {
                el.vectorText.textContent = `${status.vectorStore} · ${status.retrievalMode}`;
            }
            renderKbStats(status, tools);
        } catch (error) {
            /* 忽略：状态区不是主流程 */
        }
    }

    function renderKbStats(status, tools) {
        if (!status) {
            el.kbStats.innerHTML = '<div class="panel-empty">读取知识库状态失败。</div>';
            return;
        }
        const cards = [
            ['向量库', status.vectorStore],
            ['检索模式', status.retrievalMode],
            ['top-k / 候选', `${status.topK} / ${status.candidateK}`],
            ['分块策略', status.chunkStrategy],
            ['BM25 片段', formatInt(status.lexicalIndexSize)],
            ['Agent 工具', tools ? formatInt((tools.tools || []).length) : '-']
        ];
        el.kbStats.innerHTML = cards.map(([key, value]) => `
            <div class="stat"><div class="k">${escapeHtml(key)}</div>
                <div class="v">${escapeHtml(String(value))}</div></div>`).join('');
        icons();
    }

    function setKbBusy(busy) {
        el.kbIngest.disabled = busy;
        el.kbUpload.disabled = busy;
        el.kbCancel.hidden = !busy;
    }

    /**
     * 知识库操作（导入 / 上传）都会调用 embedding 接口，可能真的慢。
     * 这里给它一个等待上限和取消按钮：等待期间每秒更新已等待时间，
     * 超时或被取消时明确说出「后端可能仍在处理」，而不是一直转圈。
     */
    async function kbRequest(url, options, label) {
        const controller = new AbortController();
        state.kbAbort = controller;
        setKbBusy(true);
        const startedAt = Date.now();
        const render = () => {
            const seconds = Math.round((Date.now() - startedAt) / 1000);
            showKbResult(`${label}：已等待 ${seconds}s…（embedding 较慢时可点右上角「取消」）`);
        };
        render();
        const ticker = setInterval(render, 1000);
        const timer = setTimeout(() => controller.abort('timeout'), KB_TIMEOUT_MS);
        try {
            const response = await fetch(url, {...options, signal: controller.signal});
            const text = await response.text();
            let payload = null;
            try {
                payload = text ? JSON.parse(text) : null;
            } catch (error) {
                payload = null;
            }
            if (!response.ok) {
                throw new Error((payload && (payload.error || payload.message)) || `HTTP ${response.status}`);
            }
            return {payload, seconds: ((Date.now() - startedAt) / 1000).toFixed(1)};
        } catch (error) {
            if (error.name === 'AbortError') {
                const timedOut = controller.signal.reason === 'timeout';
                throw new Error(timedOut
                    ? `等待超过 ${KB_TIMEOUT_MS / 1000} 秒，已放弃等待；后端可能仍在处理，稍后点「刷新状态」看片段数`
                    : '已取消等待；后端可能仍在处理，稍后点「刷新状态」看片段数');
            }
            throw error;
        } finally {
            clearInterval(ticker);
            clearTimeout(timer);
            state.kbAbort = null;
            setKbBusy(false);
        }
    }

    async function ingestSampleDocs() {
        try {
            const {payload, seconds} = await kbRequest('/api/rag/ingest', {method: 'POST'}, '正在导入示例文档');
            document.getElementById('ingest-note').textContent =
                `已写入 ${payload.chunks} 个片段（${payload.documents} 份文档，策略 ${payload.strategy}，耗时 ${seconds}s）。`;
            showKbResult(JSON.stringify(payload, null, 2));
            await loadKbStatus();
        } catch (error) {
            showKbResult(`导入失败：${error.message}`);
        }
    }

    async function uploadDocument() {
        const file = el.uploadFile.files && el.uploadFile.files[0];
        if (!file) {
            showKbResult('先选一个文件再上传。');
            return;
        }
        try {
            const form = new FormData();
            form.append('file', file);
            if (el.uploadStrategy.value) {
                form.append('strategy', el.uploadStrategy.value);
            }
            const {payload, seconds} = await kbRequest('/api/rag/upload', {method: 'POST', body: form},
                `正在解析并索引 ${file.name}`);
            showKbResult(`耗时 ${seconds}s\n${JSON.stringify(payload, null, 2)}`);
            await loadKbStatus();
        } catch (error) {
            showKbResult(`上传失败：${error.message}`);
        }
    }

    function showKbResult(text) {
        el.kbResult.hidden = false;
        el.kbResult.textContent = text;
    }

    /* ---------------- boot --------------- */

    async function ping() {
        try {
            const response = await fetch('/api/ping');
            const payload = await response.json();
            el.statusChip.className = 'chip';
            el.statusText.textContent = '后端 UP';
        } catch (error) {
            el.statusChip.className = 'chip danger';
            el.statusText.textContent = '后端未连接';
        }
    }

    async function newSession() {
        const mode = state.mode;
        const sessionId = state.sessions[mode];
        if (sessionId) {
            try {
                await fetch(MODES[mode].clearEndpoint(sessionId), {method: 'DELETE'});
            } catch (error) {
                /* 会话清理失败不影响本地重置 */
            }
        }
        state.sessions[mode] = null;
        state.messages[mode] = [];
        mountTranscript();
        persist();
        await refreshUsage();
    }

    function bind() {
        el.composer.addEventListener('submit', (event) => {
            event.preventDefault();
            const text = el.input.value.trim();
            if (!text || state.streaming) {
                return;
            }
            el.input.value = '';
            autoGrow();
            send(text);
        });

        el.input.addEventListener('input', autoGrow);
        el.input.addEventListener('keydown', (event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
                event.preventDefault();
                el.composer.requestSubmit();
            }
        });

        el.stop.addEventListener('click', () => {
            if (state.controller) {
                state.controller.abort();
            }
        });

        document.querySelectorAll('.segmented button').forEach((button) => {
            button.addEventListener('click', () => setMode(button.dataset.mode));
        });

        document.getElementById('btn-usage').addEventListener('click', () => setPanelVisible(true));
        document.getElementById('btn-panel-close').addEventListener('click', () => setPanelVisible(false));
        document.getElementById('btn-usage-refresh').addEventListener('click', refreshUsage);
        document.getElementById('btn-usage-reset').addEventListener('click', async () => {
            await fetch('/api/observability/usage', {method: 'DELETE'});
            await refreshUsage();
        });
        document.getElementById('btn-new').addEventListener('click', newSession);

        document.getElementById('btn-kb').addEventListener('click', async () => {
            el.kbDialog.showModal();
            await loadKbStatus();
        });
        el.kbIngest.addEventListener('click', ingestSampleDocs);
        el.kbUpload.addEventListener('click', uploadDocument);
        el.kbCancel.addEventListener('click', () => {
            if (state.kbAbort) {
                state.kbAbort.abort('cancel');
            }
        });
        document.getElementById('btn-kb-refresh').addEventListener('click', loadKbStatus);
    }

    restore();
    bind();
    setMode(state.mode);
    setPanelVisible(window.innerWidth > 900);
    icons();
    ping();
    loadKbStatus();
    refreshUsage();
})();
