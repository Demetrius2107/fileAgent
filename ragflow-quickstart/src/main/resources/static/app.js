const elements = {
    connectionStatus: document.querySelector('#connection-status'),
    datasetForm: document.querySelector('#dataset-form'),
    datasetName: document.querySelector('#dataset-name'),
    datasetId: document.querySelector('#dataset-id'),
    uploadForm: document.querySelector('#upload-form'),
    documentFiles: document.querySelector('#document-files'),
    refreshDocuments: document.querySelector('#refresh-documents'),
    documentList: document.querySelector('#document-list'),
    chatForm: document.querySelector('#chat-form'),
    chatName: document.querySelector('#chat-name'),
    chatId: document.querySelector('#chat-id'),
    sessionForm: document.querySelector('#session-form'),
    sessionName: document.querySelector('#session-name'),
    sessionId: document.querySelector('#session-id'),
    questionForm: document.querySelector('#question-form'),
    question: document.querySelector('#question'),
    sendQuestion: document.querySelector('#send-question'),
    requestStatus: document.querySelector('#request-status'),
    messages: document.querySelector('#messages'),
    clearChat: document.querySelector('#clear-chat'),
    toast: document.querySelector('#toast')
};

const storedKeys = ['datasetId', 'chatId', 'sessionId'];
for (const key of storedKeys) {
    elements[key].value = localStorage.getItem(`ragflow.${key}`) || '';
    elements[key].addEventListener('change', () => saveId(key, elements[key].value.trim()));
}

elements.datasetForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    await runAction(event.submitter, async () => {
        const response = await requestJson('/api/ragflow/datasets', {
            method: 'POST',
            body: JSON.stringify({name: elements.datasetName.value.trim()})
        });
        saveId('datasetId', response.data.id);
        showToast('知识库已创建');
    });
});

elements.uploadForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const datasetId = requireId('datasetId', 'Dataset ID');
    if (!datasetId) return;
    const files = [...elements.documentFiles.files];
    if (files.length === 0) {
        showToast('请选择文件', true);
        return;
    }
    await runAction(event.submitter, async () => {
        const formData = new FormData();
        files.forEach(file => formData.append('files', file));
        await requestJson(`/api/ragflow/datasets/${encodeURIComponent(datasetId)}/documents`, {
            method: 'POST',
            body: formData
        }, false);
        showToast('上传完成，RAGFlow 已开始解析');
        await refreshDocuments();
    });
});

elements.refreshDocuments.addEventListener('click', () => runAction(elements.refreshDocuments, refreshDocuments));

elements.chatForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const datasetId = requireId('datasetId', 'Dataset ID');
    if (!datasetId) return;
    await runAction(event.submitter, async () => {
        const response = await requestJson('/api/ragflow/chats', {
            method: 'POST',
            body: JSON.stringify({
                name: elements.chatName.value.trim(),
                datasetId
            })
        });
        saveId('chatId', response.data.id);
        saveId('sessionId', '');
        showToast('聊天助手已创建');
    });
});

elements.sessionForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const chatId = requireId('chatId', 'Chat ID');
    if (!chatId) return;
    await runAction(event.submitter, async () => {
        const response = await requestJson(`/api/ragflow/chats/${encodeURIComponent(chatId)}/sessions`, {
            method: 'POST',
            body: JSON.stringify({name: elements.sessionName.value.trim()})
        });
        saveId('sessionId', response.data.id);
        clearMessages();
        showToast('会话已创建');
    });
});

elements.questionForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const chatId = requireId('chatId', 'Chat ID');
    const sessionId = requireId('sessionId', 'Session ID');
    const question = elements.question.value.trim();
    if (!chatId || !sessionId || !question) return;

    addMessage('user', question);
    const assistant = addMessage('assistant', '');
    elements.question.value = '';
    elements.sendQuestion.disabled = true;
    elements.requestStatus.textContent = 'RAGFlow 正在回答';

    try {
        const response = await fetch('/api/ragflow/chat/completions', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({chatId, sessionId, question})
        });
        if (!response.ok) throw new Error(await readError(response));
        await consumeEventStream(response.body, envelope => {
            if (envelope.data === true) {
                elements.requestStatus.textContent = '';
                return;
            }
            const data = envelope.data || {};
            if (data.answer) assistant.content.textContent += data.answer;
            renderSources(assistant.sources, data.reference);
            elements.messages.scrollTop = elements.messages.scrollHeight;
        });
        if (!assistant.content.textContent) assistant.content.textContent = 'RAGFlow 未返回文本内容';
    } catch (error) {
        assistant.content.textContent = `请求失败：${error.message}`;
        showToast(error.message, true);
    } finally {
        elements.sendQuestion.disabled = false;
        elements.requestStatus.textContent = '';
        elements.question.focus();
    }
});

elements.clearChat.addEventListener('click', clearMessages);

async function loadStatus() {
    try {
        const status = await requestJson('/api/ragflow/status');
        elements.connectionStatus.textContent = status.configured
            ? `已配置 · ${status.apiBaseUrl}`
            : '缺少 RAGFLOW_API_KEY';
        elements.connectionStatus.classList.toggle('connected', status.configured);
        if (elements.datasetId.value) await refreshDocuments();
    } catch (error) {
        elements.connectionStatus.textContent = '服务不可用';
        showToast(error.message, true);
    }
}

async function refreshDocuments() {
    const datasetId = requireId('datasetId', 'Dataset ID');
    if (!datasetId) return;
    const response = await requestJson(
        `/api/ragflow/datasets/${encodeURIComponent(datasetId)}/documents`);
    const documents = response.data?.docs || [];
    elements.documentList.innerHTML = documents.length === 0
        ? '<tr><td colspan="3" class="empty">暂无文档</td></tr>'
        : documents.map(document => {
            const progress = Math.round((document.progress || 0) * 100);
            const statusClass = document.run === 'DONE' ? 'run-done'
                : document.run === 'FAIL' ? 'run-fail' : '';
            return `<tr>
                <td>${escapeHtml(document.name || document.location || '-')}</td>
                <td class="${statusClass}">${escapeHtml(document.run || '-')}</td>
                <td>${progress}%</td>
            </tr>`;
        }).join('');
}

async function requestJson(url, options = {}, jsonContent = true) {
    const headers = new Headers(options.headers || {});
    if (jsonContent && options.body) headers.set('Content-Type', 'application/json');
    const response = await fetch(url, {...options, headers});
    if (!response.ok) throw new Error(await readError(response));
    return response.json();
}

async function readError(response) {
    const text = await response.text();
    try {
        const body = JSON.parse(text);
        return body.detail || body.message || `HTTP ${response.status}`;
    } catch {
        return text || `HTTP ${response.status}`;
    }
}

async function consumeEventStream(stream, onEvent) {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = blocks.pop() || '';
        blocks.forEach(block => {
            const data = block.split(/\r?\n/)
                .filter(line => line.startsWith('data:'))
                .map(line => line.slice(5).trimStart())
                .join('\n');
            if (data) onEvent(JSON.parse(data));
        });
        if (done) break;
    }
}

function addMessage(role, text) {
    const emptyState = elements.messages.querySelector('.empty-state');
    if (emptyState) emptyState.remove();
    const node = document.createElement('article');
    node.className = `message ${role}`;
    node.innerHTML = `
        <div class="message-role">${role === 'user' ? '你' : 'RAGFlow'}</div>
        <div class="message-content"></div>
        <div class="sources"></div>`;
    const content = node.querySelector('.message-content');
    content.textContent = text;
    elements.messages.appendChild(node);
    elements.messages.scrollTop = elements.messages.scrollHeight;
    return {node, content, sources: node.querySelector('.sources')};
}

function renderSources(container, reference) {
    const chunks = reference?.chunks || [];
    for (const chunk of chunks) {
        const label = chunk.document_name || chunk.doc_name || chunk.id;
        if (!label || container.querySelector(`[data-source="${cssEscape(label)}"]`)) continue;
        const chip = document.createElement('span');
        chip.className = 'source-chip';
        chip.dataset.source = label;
        chip.textContent = label;
        container.appendChild(chip);
    }
}

function clearMessages() {
    elements.messages.innerHTML = `
        <div class="empty-state">
            <strong>等待提问</strong>
            <span>当前页面消息已清空，RAGFlow 会话历史仍保留</span>
        </div>`;
}

async function runAction(button, action) {
    button.disabled = true;
    try {
        await action();
    } catch (error) {
        showToast(error.message, true);
    } finally {
        button.disabled = false;
    }
}

function requireId(key, label) {
    const value = elements[key].value.trim();
    if (!value) showToast(`请先填写 ${label}`, true);
    return value;
}

function saveId(key, value) {
    elements[key].value = value;
    if (value) localStorage.setItem(`ragflow.${key}`, value);
    else localStorage.removeItem(`ragflow.${key}`);
}

function showToast(message, error = false) {
    elements.toast.textContent = message;
    elements.toast.classList.toggle('error', error);
    elements.toast.classList.add('visible');
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => elements.toast.classList.remove('visible'), 3200);
}

function escapeHtml(value) {
    const node = document.createElement('span');
    node.textContent = value;
    return node.innerHTML;
}

function cssEscape(value) {
    return window.CSS?.escape ? window.CSS.escape(value) : value.replace(/["\\]/g, '\\$&');
}

loadStatus();
