/* 文件智能助手工作台脚本：同源 API + SSE 流式对话 */
'use strict';

(() => {
    const $ = (id) => document.getElementById(id);

    /* SVG 图标常量 */
    const SVG = {
        userAvatar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>',
        botAvatar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18"><rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="12" cy="5" r="2"/><path d="M12 7v4"/><line x1="8" y1="16" x2="8" y2="16"/><line x1="16" y1="16" x2="16" y2="16"/></svg>',
        emptyChat: '<svg viewBox="0 0 80 80" fill="none" width="80" height="80"><rect x="10" y="16" width="60" height="40" rx="8" fill="#eef1fe" stroke="#4f6ef7" stroke-width="1.5"/><circle cx="28" cy="36" r="3" fill="#4f6ef7"/><circle cx="40" cy="36" r="3" fill="#4f6ef7"/><circle cx="52" cy="36" r="3" fill="#4f6ef7"/><path d="M24 56 L20 64 L32 56" fill="#eef1fe" stroke="#4f6ef7" stroke-width="1.5" stroke-linejoin="round"/></svg>',
        fileDoc: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M10 1H4a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V5z"/><path d="M10 1v4h4"/><line x1="6" y1="9" x2="10" y2="9"/><line x1="6" y1="12" x2="9" y2="12"/></svg>',
        sourceIcon: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="12" height="12"><path d="M13 12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h4l4 4z"/><path d="M9 2v4h4"/></svg>',
    };

    const sessionList = $('session-list');
    const newSessionButton = $('new-session-button');
    const chatTitle = $('chat-title');
    const messageList = $('message-list');
    const chatForm = $('chat-form');
    const promptInput = $('prompt-input');
    const sendButton = $('send-button');
    const stopButton = $('stop-button');
    const knowledgeList = $('knowledge-list');
    const uploadButton = $('upload-button');
    const uploadDialog = $('upload-dialog');
    const uploadForm = $('upload-form');
    const uploadName = $('upload-name');
    const uploadTag = $('upload-tag');
    const uploadFiles = $('upload-files');
    const uploadError = $('upload-error');
    const modelSettingsButton = $('toggle-model-settings');
    const modelSettingsDialog = $('model-settings-dialog');
    const modelSettingsForm = $('model-settings-form');
    const modelConfigList = $('model-config-list');
    const providerSelect = $('provider-select');
    const baseUrlInput = $('base-url-input');
    const apiKeyInput = $('api-key-input');
    const modelNameInput = $('model-name-input');
    const modelConfigError = $('model-config-error');

    let currentSessionId = null;
    let abortController = null;
    let streaming = false;

    /* ---------- 通用 ---------- */

    async function api(path, options) {
        const response = await fetch(path, options);
        let body;
        try {
            body = await response.json();
        } catch (e) {
            throw new Error(`服务响应异常（HTTP ${response.status}）`);
        }
        if (body.code !== 0) {
            throw new Error(body.message || `请求失败（HTTP ${response.status}）`);
        }
        return body.data;
    }

    function setStreaming(active) {
        streaming = active;
        sendButton.disabled = active || !currentSessionId;
        stopButton.hidden = !active;
    }

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text !== undefined) { node.textContent = text; }
        return node;
    }

    function svgEl(svgString) {
        const template = document.createElement('template');
        template.innerHTML = svgString.trim();
        return template.content.firstChild;
    }

    /* ---------- 会话 ---------- */

    async function loadSessions() {
        const sessions = await api('/api/sessions');
        sessionList.replaceChildren();
        if (!sessions || sessions.length === 0) {
            sessionList.appendChild(el('li', 'list-empty', '暂无会话，点击"新建会话"开始'));
            return;
        }
        for (const session of sessions) {
            const item = el('li', null, session.title);
            if (session.id === currentSessionId) { item.classList.add('active'); }
            item.addEventListener('click', () => selectSession(session.id, session.title));
            sessionList.appendChild(item);
        }
    }

    async function createSession() {
        const session = await api('/api/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: null })
        });
        await loadSessions();
        await selectSession(session.id, session.title);
    }

    async function selectSession(sessionId, title) {
        if (streaming) { abortController && abortController.abort(); }
        currentSessionId = sessionId;
        chatTitle.textContent = title;
        sendButton.disabled = false;
        await loadSessions();
        await loadMessages();
    }

    async function loadMessages() {
        const messages = await api(`/api/sessions/${currentSessionId}/messages`);
        messageList.replaceChildren();
        if (!messages || messages.length === 0) {
            renderEmptyChat();
            return;
        }
        for (const message of messages) {
            appendMessage(message.role, message.content);
        }
    }

    function renderEmptyChat() {
        const hint = el('div', 'chat-hint');
        const iconWrap = el('div', 'empty-icon');
        iconWrap.appendChild(svgEl(SVG.emptyChat));
        hint.appendChild(iconWrap);
        hint.appendChild(el('div', 'empty-title', '开始对话'));
        hint.appendChild(el('div', 'empty-desc', '上传知识文件后即可提问，回答将标明来源。'));
        messageList.appendChild(hint);
    }

    function appendMessage(role, content) {
        const wrap = el('div', `message ${role === 'USER' ? 'user' : 'assistant'}`);
        const avatar = el('div', 'message-avatar');
        avatar.innerHTML = role === 'USER' ? SVG.userAvatar : SVG.botAvatar;
        wrap.appendChild(avatar);
        const body = el('div', 'message-body');
        body.appendChild(el('div', 'message-bubble', content));
        wrap.appendChild(body);
        messageList.appendChild(wrap);
        messageList.scrollTop = messageList.scrollHeight;
        return wrap;
    }

    /* ---------- SSE 流式对话 ---------- */

    chatForm.addEventListener('submit', (event) => {
        event.preventDefault();
        if (streaming || !currentSessionId) { return; }
        const prompt = promptInput.value.trim();
        if (!prompt) { return; }
        sendMessage(prompt);
    });

    promptInput.addEventListener('input', () => {
        promptInput.style.height = 'auto';
        promptInput.style.height = `${Math.min(promptInput.scrollHeight, 120)}px`;
    });

    stopButton.addEventListener('click', () => { abortController && abortController.abort(); });

    async function sendMessage(prompt) {
        promptInput.value = '';
        appendMessage('USER', prompt);
        const assistant = appendMessage('ASSISTANT', '');
        const bubble = assistant.querySelector('.message-bubble');

        abortController = new AbortController();
        setStreaming(true);
        let content = '';
        try {
            await streamChat(prompt, (event) => {
                if (event.type === 'message') {
                    content += event.content;
                    bubble.textContent = content;
                    messageList.scrollTop = messageList.scrollHeight;
                } else if (event.type === 'sources') {
                    renderSources(assistant, event);
                } else if (event.type === 'done') {
                    assistant.dataset.messageId = String(event.messageId);
                } else if (event.type === 'error') {
                    assistant.querySelector('.message-body')
                        .appendChild(el('div', 'message-error', `出错了：${event.message || event.code || '未知错误'}`));
                }
            });
        } catch (e) {
            const body = assistant.querySelector('.message-body');
            if (e.name === 'AbortError') {
                body.appendChild(el('div', 'message-error', '已停止生成。'));
            } else {
                body.appendChild(el('div', 'message-error', `出错了：${e.message}`));
            }
        } finally {
            setStreaming(false);
            abortController = null;
            messageList.scrollTop = messageList.scrollHeight;
        }
    }

    async function streamChat(prompt, onEvent) {
        const response = await fetch(`/api/sessions/${currentSessionId}/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
            body: JSON.stringify({ prompt }),
            signal: abortController.signal
        });
        if (!response.ok || !response.body) {
            let message = `HTTP ${response.status}`;
            try {
                const body = await response.json();
                if (body && body.message) { message = body.message; }
            } catch (e) { /* 非 JSON 错误体 */ }
            throw new Error(message);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
            const { done, value } = await reader.read();
            if (done) { break; }
            buffer += decoder.decode(value, { stream: true });
            let separator;
            while ((separator = buffer.indexOf('\n\n')) >= 0) {
                const block = buffer.slice(0, separator);
                buffer = buffer.slice(separator + 2);
                const parsed = parseSseBlock(block);
                if (parsed) { onEvent(parsed); }
            }
        }
        const rest = parseSseBlock(buffer);
        if (rest) { onEvent(rest); }
    }

    function parseSseBlock(block) {
        let eventName = null;
        let data = '';
        for (const line of block.split(/\r?\n/)) {
            if (line.startsWith('event:')) { eventName = line.slice(6).trim(); }
            else if (line.startsWith('data:')) { data += line.slice(5).trim(); }
        }
        if (!data) { return null; }
        try {
            const payload = JSON.parse(data);
            return payload && payload.type ? payload : { type: eventName, ...payload };
        } catch (e) {
            return eventName ? { type: eventName, content: data } : null;
        }
    }

    function renderSources(assistant, event) {
        const files = event.files || [];
        let text;
        if (event.answerSource === 'MODEL_GENERAL' || files.length === 0) {
            text = '来源：模型通用知识';
        } else {
            text = `来源：${files.join('、')}`;
        }
        const sources = el('div', 'message-sources');
        sources.appendChild(svgEl(SVG.sourceIcon));
        sources.appendChild(document.createTextNode(text));
        assistant.querySelector('.message-body').appendChild(sources);
    }

    /* ---------- 知识库 ---------- */

    async function loadKnowledge() {
        const files = await api('/api/rag-files');
        knowledgeList.replaceChildren();
        if (!files || files.length === 0) {
            knowledgeList.appendChild(el('li', 'list-empty', '暂无知识文件，点击"上传"添加'));
            return;
        }
        for (const file of files) {
            const item = el('li');
            const nameEl = el('div', 'knowledge-file-name');
            nameEl.appendChild(svgEl(SVG.fileDoc));
            nameEl.appendChild(document.createTextNode(file.filename));
            item.appendChild(nameEl);
            const meta = el('div', 'knowledge-file-meta');
            const statusSpan = el('span', file.status === 'READY' ? 'status-ready' : 'status-other', `状态 ${file.status || '-'}`);
            meta.appendChild(statusSpan);
            meta.appendChild(el('span', null, `分块 ${file.chunkCount == null ? '-' : file.chunkCount}`));
            meta.appendChild(el('span', null, formatTime(file.createdAt)));
            item.appendChild(meta);
            knowledgeList.appendChild(item);
        }
    }

    function formatTime(iso) {
        if (!iso) { return '-'; }
        return iso.replace('T', ' ').slice(0, 19);
    }

    uploadButton.addEventListener('click', () => {
        uploadError.hidden = true;
        uploadError.textContent = '';
        uploadDialog.showModal();
    });

    $('upload-cancel').addEventListener('click', () => { uploadDialog.close(); });

    uploadForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const name = uploadName.value.trim();
        const tag = uploadTag.value.trim();
        if (!name || !tag || !uploadFiles.files || uploadFiles.files.length === 0) {
            showUploadError('请填写知识库名称、标签并选择至少一个文件');
            return;
        }
        const formData = new FormData();
        formData.append('name', name);
        formData.append('tag', tag);
        for (const file of uploadFiles.files) { formData.append('files', file); }
        try {
            await api('/api/rag-files/upload', { method: 'POST', body: formData });
            uploadDialog.close();
            uploadForm.reset();
            await loadKnowledge();
        } catch (e) {
            showUploadError(e.message);
        }
    });

    function showUploadError(message) {
        uploadError.textContent = message;
        uploadError.hidden = false;
    }

    /* ---------- 模型设置 ---------- */

    const PROVIDER_NAMES = {
        DEEPSEEK: 'DeepSeek', ZHIPU: '智谱 GLM', DASHSCOPE: '通义 Qwen',
        MOONSHOT: 'Kimi', OPENAI: 'OpenAI', CUSTOM: '自定义'
    };

    function showModelConfigHint(message, ok) {
        modelConfigError.textContent = message;
        modelConfigError.classList.toggle('ok', !!ok);
        modelConfigError.hidden = false;
    }

    async function loadModelConfigs() {
        const configs = await api('/api/model-providers');
        modelConfigList.replaceChildren();
        if (!configs || configs.length === 0) {
            modelConfigList.appendChild(el('li', 'list-empty', '暂无模型配置，聊天将使用服务端默认模型（环境变量）'));
            return;
        }
        for (const config of configs) {
            modelConfigList.appendChild(renderModelConfigItem(config));
        }
    }

    function renderModelConfigItem(config) {
        const item = el('li', 'model-config-item' + (config.active ? ' active' : ''));
        const head = el('div', 'model-config-head');
        head.appendChild(el('span', 'model-provider-tag', PROVIDER_NAMES[config.provider] || config.provider));
        head.appendChild(el('span', 'model-config-name', config.chatModel));
        if (config.active) { head.appendChild(el('span', 'badge-success', '使用中')); }
        item.appendChild(head);

        const meta = el('div', 'model-config-meta');
        meta.appendChild(el('span', null, `Key ${config.apiKeyMasked || '****'}`));
        meta.appendChild(el('span', null, config.baseUrl));
        item.appendChild(meta);

        const actions = el('div', 'model-config-actions');
        if (!config.active) {
            const activateButton = el('button', 'mini-button', '启用');
            activateButton.type = 'button';
            activateButton.addEventListener('click', async () => {
                try {
                    await api(`/api/model-providers/${config.id}/activate`, { method: 'PUT' });
                    await loadModelConfigs();
                } catch (e) { showModelConfigHint(e.message, false); }
            });
            actions.appendChild(activateButton);
        }
        const testButton = el('button', 'mini-button', '测试');
        testButton.type = 'button';
        testButton.addEventListener('click', async () => {
            testButton.disabled = true;
            testButton.textContent = '测试中…';
            try {
                const result = await api(`/api/model-providers/${config.id}/test`, { method: 'POST' });
                showModelConfigHint(result, true);
            } catch (e) {
                showModelConfigHint(e.message, false);
            } finally {
                testButton.disabled = false;
                testButton.textContent = '测试';
            }
        });
        actions.appendChild(testButton);
        const deleteButton = el('button', 'mini-button danger', '删除');
        deleteButton.type = 'button';
        deleteButton.addEventListener('click', async () => {
            if (!confirm(`删除 ${PROVIDER_NAMES[config.provider] || config.provider} 的 ${config.chatModel} 配置？`)) { return; }
            try {
                await api(`/api/model-providers/${config.id}`, { method: 'DELETE' });
                await loadModelConfigs();
            } catch (e) { showModelConfigHint(e.message, false); }
        });
        actions.appendChild(deleteButton);
        item.appendChild(actions);
        return item;
    }

    modelSettingsButton.addEventListener('click', () => {
        modelConfigError.hidden = true;
        modelSettingsDialog.showModal();
        loadModelConfigs().catch((e) => showModelConfigHint(e.message, false));
    });

    $('model-config-cancel').addEventListener('click', () => { modelSettingsDialog.close(); });

    modelSettingsForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const provider = providerSelect.value;
        const apiKey = apiKeyInput.value;
        const chatModel = modelNameInput.value.trim();
        if (!provider || !apiKey || !chatModel) {
            showModelConfigHint('请选择厂商并填写 API Key 和模型名', false);
            return;
        }
        try {
            const saved = await api('/api/model-providers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    provider,
                    baseUrl: baseUrlInput.value.trim() || null,
                    apiKey,
                    chatModel,
                    temperature: null
                })
            });
            apiKeyInput.value = '';
            await loadModelConfigs();
            showModelConfigHint(saved.active ? `已保存并启用 ${PROVIDER_NAMES[provider]} / ${saved.chatModel}` : '配置已保存（未启用）', true);
        } catch (e) {
            showModelConfigHint(e.message, false);
        }
    });

    /* ---------- 侧栏抽屉（窄屏） ---------- */

    $('toggle-session-drawer').addEventListener('click', () => {
        $('session-panel').classList.toggle('open');
        $('knowledge-panel').classList.remove('open');
    });

    $('toggle-knowledge-drawer').addEventListener('click', () => {
        $('knowledge-panel').classList.toggle('open');
        $('session-panel').classList.remove('open');
    });

    /* ---------- 初始化 ---------- */

    newSessionButton.addEventListener('click', () => {
        newSessionButton.disabled = true;
        createSession().catch((e) => alert(`创建会话失败：${e.message}`))
            .finally(() => { newSessionButton.disabled = false; });
    });

    async function init() {
        try {
            await loadSessions();
            await loadKnowledge();
        } catch (e) {
            messageList.appendChild(el('div', 'chat-hint', `加载失败：${e.message}`));
        }
    }

    init();
})();
