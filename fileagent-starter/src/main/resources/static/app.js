/* 文件智能助手工作台脚本：同源 API + SSE 流式对话 */
'use strict';

(() => {
    const $ = (id) => document.getElementById(id);

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
        if (className) {
            node.className = className;
        }
        if (text !== undefined) {
            node.textContent = text;
        }
        return node;
    }

    /* ---------- 会话 ---------- */

    async function loadSessions() {
        const sessions = await api('/api/sessions');
        sessionList.replaceChildren();
        if (!sessions || sessions.length === 0) {
            sessionList.appendChild(el('li', 'list-empty', '暂无会话，点击“新建会话”开始'));
            return;
        }
        for (const session of sessions) {
            const item = el('li', null, session.title);
            if (session.id === currentSessionId) {
                item.classList.add('active');
            }
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
        if (streaming) {
            abortController && abortController.abort();
        }
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
            messageList.appendChild(el('div', 'chat-hint', '上传知识文件后即可提问，回答将标明来源。'));
            return;
        }
        for (const message of messages) {
            appendMessage(message.role, message.content);
        }
    }

    function appendMessage(role, content) {
        const wrap = el('div', `message ${role === 'USER' ? 'user' : 'assistant'}`);
        wrap.appendChild(el('div', 'message-bubble', content));
        messageList.appendChild(wrap);
        messageList.scrollTop = messageList.scrollHeight;
        return wrap;
    }

    /* ---------- SSE 流式对话 ---------- */

    chatForm.addEventListener('submit', (event) => {
        event.preventDefault();
        if (streaming || !currentSessionId) {
            return;
        }
        const prompt = promptInput.value.trim();
        if (!prompt) {
            return;
        }
        sendMessage(prompt);
    });

    promptInput.addEventListener('input', () => {
        promptInput.style.height = 'auto';
        promptInput.style.height = `${Math.min(promptInput.scrollHeight, 120)}px`;
    });

    stopButton.addEventListener('click', () => {
        abortController && abortController.abort();
    });

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
                    assistant.appendChild(el('div', 'message-error',
                        `出错了：${event.message || event.code || '未知错误'}`));
                }
            });
        } catch (e) {
            if (e.name === 'AbortError') {
                assistant.appendChild(el('div', 'message-error', '已停止生成。'));
            } else {
                assistant.appendChild(el('div', 'message-error', `出错了：${e.message}`));
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
                if (body && body.message) {
                    message = body.message;
                }
            } catch (e) { /* 非 JSON 错误体 */ }
            throw new Error(message);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        // 跨 chunk 缓冲区：一次 read() 不一定恰好是一条事件
        let buffer = '';
        for (;;) {
            const { done, value } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            let separator;
            while ((separator = buffer.indexOf('\n\n')) >= 0) {
                const block = buffer.slice(0, separator);
                buffer = buffer.slice(separator + 2);
                const parsed = parseSseBlock(block);
                if (parsed) {
                    onEvent(parsed);
                }
            }
        }
        // 冲刷残留缓冲
        const rest = parseSseBlock(buffer);
        if (rest) {
            onEvent(rest);
        }
    }

    function parseSseBlock(block) {
        let eventName = null;
        let data = '';
        for (const line of block.split(/\r?\n/)) {
            if (line.startsWith('event:')) {
                eventName = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                data += line.slice(5).trim();
            }
        }
        if (!data) {
            return null;
        }
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
        assistant.appendChild(el('div', 'message-sources', text));
    }

    /* ---------- 知识库 ---------- */

    async function loadKnowledge() {
        const files = await api('/api/rag-files');
        knowledgeList.replaceChildren();
        if (!files || files.length === 0) {
            knowledgeList.appendChild(el('li', 'list-empty', '暂无知识文件，点击“上传”添加'));
            return;
        }
        for (const file of files) {
            const item = el('li');
            item.appendChild(el('div', 'knowledge-file-name', file.filename));
            const meta = el('div', 'knowledge-file-meta');
            meta.appendChild(el('span', null, `状态 ${file.status || '-'}`));
            meta.appendChild(el('span', null, `分块 ${file.chunkCount == null ? '-' : file.chunkCount}`));
            meta.appendChild(el('span', null, formatTime(file.createdAt)));
            item.appendChild(meta);
            knowledgeList.appendChild(item);
        }
    }

    function formatTime(iso) {
        if (!iso) {
            return '-';
        }
        return iso.replace('T', ' ').slice(0, 19);
    }

    uploadButton.addEventListener('click', () => {
        uploadError.hidden = true;
        uploadError.textContent = '';
        uploadDialog.showModal();
    });

    $('upload-cancel').addEventListener('click', () => {
        uploadDialog.close();
    });

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
        for (const file of uploadFiles.files) {
            formData.append('files', file);
        }
        try {
            await api('/api/rag-files/upload', { method: 'POST', body: formData });
            uploadDialog.close();
            uploadForm.reset();
            await loadKnowledge();
        } catch (e) {
            // 失败时保留用户已填内容和文件选择
            showUploadError(e.message);
        }
    });

    function showUploadError(message) {
        uploadError.textContent = message;
        uploadError.hidden = false;
    }

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
