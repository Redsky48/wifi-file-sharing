(function () {
    'use strict';

    const PIN_KEY = 'wifishare_pin';

    const loginView = document.getElementById('login-view');
    const appView = document.getElementById('app-view');
    const pinDots = document.querySelectorAll('#pin-dots .pin-dot');
    const loginError = document.getElementById('login-error');

    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('file-input');
    const uploadList = document.getElementById('upload-list');
    const filesList = document.getElementById('files-list');
    const filesEmpty = document.getElementById('files-empty');
    const breadcrumb = document.getElementById('breadcrumb');
    const refreshBtn = document.getElementById('refresh');
    const toast = document.getElementById('toast');

    const previewOverlay = document.getElementById('preview-overlay');
    const previewName = document.getElementById('preview-name');
    const previewContent = document.getElementById('preview-content');
    const previewDownload = document.getElementById('preview-download');
    const previewClose = document.getElementById('preview-close');

    let allowUploads = true;
    let allowDelete = false;
    let currentPath = '';
    let pin = localStorage.getItem(PIN_KEY) || '';
    let entered = '';
    let submitting = false;

    // ---------- auth helpers ----------

    function authHeaders() {
        if (!pin) return {};
        return { 'Authorization': 'Basic ' + btoa('user:' + pin) };
    }

    function setAuthCookie() {
        if (!pin) {
            document.cookie = 'wifishare_auth=; path=/; max-age=0; SameSite=Strict';
            return;
        }
        const token = btoa('user:' + pin);
        // 24h expiry — long enough to be useful, short enough to expire if PIN changes
        document.cookie = 'wifishare_auth=' + token + '; path=/; max-age=86400; SameSite=Strict';
    }

    function clearAuthCookie() {
        document.cookie = 'wifishare_auth=; path=/; max-age=0; SameSite=Strict';
    }

    async function authedFetch(url, options) {
        options = options || {};
        const headers = Object.assign({}, options.headers || {}, authHeaders());
        const res = await fetch(url, Object.assign({}, options, { headers }));
        if (res.status === 401) {
            // PIN became invalid (changed on phone, etc.) — re-login
            pin = '';
            localStorage.removeItem(PIN_KEY);
            showLogin();
            throw new Error('unauthorized');
        }
        return res;
    }

    async function tryAuth(candidate) {
        try {
            const res = await fetch('/api/files', {
                headers: { 'Authorization': 'Basic ' + btoa('user:' + (candidate || '')) },
            });
            if (res.ok) return 'ok';
            if (res.status === 401) return 'wrong';
            return 'error';
        } catch (e) {
            return 'error';
        }
    }

    function showLogin() {
        clearAuthCookie();
        loginView.classList.remove('hidden');
        appView.classList.add('hidden');
        entered = '';
        updateDots();
        loginError.classList.add('hidden');
        document.body.focus();
    }

    function showApp() {
        setAuthCookie();
        loginView.classList.add('hidden');
        appView.classList.remove('hidden');
        loadFiles();
    }

    function updateDots() {
        pinDots.forEach((d, i) => {
            d.classList.toggle('filled', i < entered.length);
        });
    }

    async function onDigit(d) {
        if (submitting) return;
        if (entered.length >= 6) return;
        loginError.classList.add('hidden');
        entered += d;
        updateDots();
        if (entered.length === 6) {
            submitting = true;
            const candidate = entered;
            // Brief pause so user sees the last dot fill
            await new Promise(r => setTimeout(r, 120));
            const result = await tryAuth(candidate);
            submitting = false;
            if (result === 'ok') {
                pin = candidate;
                localStorage.setItem(PIN_KEY, pin);
                showApp();
            } else {
                // wrong — shake, clear, allow retry
                loginError.textContent = result === 'wrong' ? 'Wrong PIN' : 'Connection error';
                loginError.classList.remove('hidden');
                entered = '';
                updateDots();
            }
        }
    }

    function onBackspace() {
        if (submitting) return;
        if (entered.length === 0) return;
        loginError.classList.add('hidden');
        entered = entered.slice(0, -1);
        updateDots();
    }

    function wireKeypad() {
        document.querySelectorAll('.key[data-digit]').forEach(btn => {
            btn.addEventListener('click', () => onDigit(btn.dataset.digit));
        });
        document.getElementById('pin-back').addEventListener('click', onBackspace);

        document.addEventListener('keydown', (e) => {
            if (loginView.classList.contains('hidden')) return;
            if (e.key >= '0' && e.key <= '9') {
                onDigit(e.key);
                e.preventDefault();
            } else if (e.key === 'Backspace') {
                onBackspace();
                e.preventDefault();
            }
        });
    }

    async function bootstrap() {
        wireKeypad();
        // First try existing PIN (or no PIN at all)
        const result = await tryAuth(pin);
        if (result === 'ok') {
            showApp();
        } else {
            // 'wrong' (need PIN) or 'error' (network) — show login either way
            pin = '';
            localStorage.removeItem(PIN_KEY);
            showLogin();
        }
    }

    function fmtSize(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }

    function fmtDate(ms) {
        if (!ms) return '';
        const d = new Date(ms);
        const now = new Date();
        if (d.toDateString() === now.toDateString()) {
            return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }
        return d.toLocaleDateString();
    }

    function showToast(msg, isError) {
        toast.textContent = msg;
        toast.classList.remove('hidden', 'error');
        if (isError) toast.classList.add('error');
        clearTimeout(showToast.t);
        showToast.t = setTimeout(() => toast.classList.add('hidden'), 2500);
    }

    async function loadFiles() {
        try {
            const url = '/api/files?path=' + encodeURIComponent(currentPath);
            const res = await authedFetch(url);
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            allowUploads = data.allowUploads;
            allowDelete = data.allowDelete;
            renderBreadcrumb();
            renderEntries(data.entries || []);
            dropzone.classList.toggle('hidden', !allowUploads);
        } catch (e) {
            if (e && e.message === 'unauthorized') return;
            showToast('Failed to load: ' + e.message, true);
        }
    }

    function renderBreadcrumb() {
        breadcrumb.innerHTML = '';
        const parts = currentPath.split('/').filter(Boolean);
        addCrumb('Home', '');
        let acc = '';
        parts.forEach((p, i) => {
            const sep = document.createElement('span');
            sep.className = 'breadcrumb-sep';
            sep.textContent = '›';
            breadcrumb.appendChild(sep);
            acc = acc ? acc + '/' + p : p;
            addCrumb(p, acc, i === parts.length - 1);
        });
    }

    function addCrumb(label, target, isCurrent) {
        const btn = document.createElement('button');
        btn.className = 'breadcrumb-crumb' + (isCurrent ? ' current' : '');
        btn.textContent = label;
        if (!isCurrent) {
            btn.onclick = () => navigateTo(target);
        }
        breadcrumb.appendChild(btn);
    }

    function navigateTo(path) {
        currentPath = path.replace(/^\/+|\/+$/g, '');
        loadFiles();
    }

    function iconFor(entry) {
        if (entry.type === 'folder') return '📁';
        const m = (entry.mime || '').toLowerCase();
        if (m.startsWith('image/')) return '🖼️';
        if (m.startsWith('video/')) return '🎬';
        if (m.startsWith('audio/')) return '🎵';
        if (m === 'application/pdf') return '📕';
        if (m.startsWith('text/') || m === 'application/json') return '📝';
        if (m === 'application/zip' || m === 'application/x-rar-compressed') return '📦';
        if (m === 'application/vnd.android.package-archive') return '📲';
        return '📄';
    }

    function fullPathOf(entry) {
        return currentPath ? currentPath + '/' + entry.name : entry.name;
    }

    function renderEntries(entries) {
        filesList.innerHTML = '';
        filesEmpty.classList.toggle('hidden', entries.length > 0);

        for (const e of entries) {
            const li = document.createElement('li');
            li.className = 'file-row ' + (e.type === 'folder' ? 'is-folder' : 'is-file');

            const icon = document.createElement('div');
            icon.className = 'file-icon';
            icon.textContent = iconFor(e);

            const name = document.createElement('div');
            name.className = 'file-name';
            name.textContent = e.name;
            name.title = e.name;

            const meta = document.createElement('div');
            meta.className = 'file-meta';
            meta.textContent = e.type === 'folder'
                ? fmtDate(e.modified)
                : fmtSize(e.size) + ' · ' + fmtDate(e.modified);

            const actions = document.createElement('div');
            actions.className = 'file-actions';

            if (e.type === 'file') {
                const dl = document.createElement('button');
                dl.className = 'icon';
                dl.textContent = 'Download';
                dl.onclick = (ev) => { ev.stopPropagation(); downloadFile(e); };
                actions.appendChild(dl);
            }

            if (allowDelete) {
                const del = document.createElement('button');
                del.className = 'icon danger';
                del.textContent = 'Delete';
                del.onclick = (ev) => { ev.stopPropagation(); deleteEntry(e); };
                actions.appendChild(del);
            }

            li.onclick = () => {
                if (e.type === 'folder') navigateTo(fullPathOf(e));
                else openPreview(e);
            };

            li.appendChild(icon);
            li.appendChild(name);
            li.appendChild(meta);
            li.appendChild(actions);
            filesList.appendChild(li);
        }
    }

    async function deleteEntry(entry) {
        if (!confirm('Delete "' + entry.name + '"?')) return;
        try {
            const r = await authedFetch('/api/delete?path=' + encodeURIComponent(fullPathOf(entry)),
                { method: 'DELETE' });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            showToast('Deleted ' + entry.name);
            loadFiles();
        } catch (e) {
            if (e && e.message === 'unauthorized') return;
            showToast('Delete failed: ' + e.message, true);
        }
    }

    function downloadFile(entry) {
        // Direct navigation — server returns the file with attachment disposition.
        // Cookie carries auth so this works as a normal download.
        const url = '/api/download?path=' + encodeURIComponent(fullPathOf(entry));
        const a = document.createElement('a');
        a.href = url;
        a.download = entry.name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    // ---------- preview modal ----------

    function openPreview(entry) {
        const path = fullPathOf(entry);
        const inlineUrl = '/api/download?path=' + encodeURIComponent(path) + '&inline=1';
        const downloadUrl = '/api/download?path=' + encodeURIComponent(path);

        previewName.textContent = entry.name;
        previewDownload.href = downloadUrl;
        previewDownload.setAttribute('download', entry.name);

        previewContent.innerHTML = '';
        const mime = (entry.mime || '').toLowerCase();
        let el;

        if (mime.startsWith('image/')) {
            el = document.createElement('img');
            el.src = inlineUrl;
            el.alt = entry.name;
        } else if (mime.startsWith('video/')) {
            el = document.createElement('video');
            el.src = inlineUrl;
            el.controls = true;
            el.autoplay = true;
            el.preload = 'metadata';
        } else if (mime.startsWith('audio/')) {
            el = document.createElement('audio');
            el.src = inlineUrl;
            el.controls = true;
            el.autoplay = true;
        } else if (mime === 'application/pdf') {
            el = document.createElement('iframe');
            el.src = inlineUrl;
        } else if (mime.startsWith('text/') || mime === 'application/json') {
            el = document.createElement('pre');
            el.textContent = 'Loading…';
            authedFetch(inlineUrl).then(r => r.text()).then(txt => {
                el.textContent = txt.length > 500_000
                    ? txt.slice(0, 500_000) + '\n\n…(truncated)'
                    : txt;
            }).catch(() => { el.textContent = 'Failed to load'; });
        } else {
            el = document.createElement('div');
            el.className = 'preview-fallback';
            el.innerHTML = '<p>No preview available for this file type.</p>';
            const dl = document.createElement('a');
            dl.className = 'preview-btn';
            dl.href = downloadUrl;
            dl.textContent = 'Download';
            dl.setAttribute('download', entry.name);
            el.appendChild(dl);
        }

        previewContent.appendChild(el);
        previewOverlay.classList.remove('hidden');
    }

    function closePreview() {
        // Stop any media playback before tearing down
        const media = previewContent.querySelector('video, audio');
        if (media) { try { media.pause(); } catch (_) {} }
        previewContent.innerHTML = '';
        previewOverlay.classList.add('hidden');
    }

    previewClose.addEventListener('click', closePreview);
    previewOverlay.addEventListener('click', (e) => {
        if (e.target === previewOverlay) closePreview();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !previewOverlay.classList.contains('hidden')) {
            closePreview();
        }
    });

    function uploadFile(file) {
        const row = document.createElement('div');
        row.className = 'upload-row';
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = file.name;
        const bar = document.createElement('div');
        bar.className = 'bar';
        const fill = document.createElement('div');
        bar.appendChild(fill);
        const status = document.createElement('span');
        status.className = 'status';
        status.textContent = '0%';
        row.appendChild(name);
        row.appendChild(bar);
        row.appendChild(status);
        uploadList.appendChild(row);

        const form = new FormData();
        form.append('file', file, file.name);

        const xhr = new XMLHttpRequest();
        const url = '/api/upload?path=' + encodeURIComponent(currentPath);
        xhr.open('POST', url);
        if (pin) {
            xhr.setRequestHeader('Authorization', 'Basic ' + btoa('user:' + pin));
        }
        xhr.upload.onprogress = (e) => {
            if (!e.lengthComputable) return;
            const pct = Math.round((e.loaded / e.total) * 100);
            fill.style.width = pct + '%';
            status.textContent = pct + '%';
        };
        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                fill.style.width = '100%';
                status.textContent = 'Done';
                status.classList.add('ok');
                setTimeout(() => row.remove(), 1500);
                loadFiles();
            } else if (xhr.status === 401) {
                status.textContent = 'Auth lost';
                status.classList.add('err');
                pin = '';
                localStorage.removeItem(PIN_KEY);
                showLogin();
            } else {
                status.textContent = 'Failed (' + xhr.status + ')';
                status.classList.add('err');
            }
        };
        xhr.onerror = () => {
            status.textContent = 'Error';
            status.classList.add('err');
        };
        xhr.send(form);
    }

    function uploadFiles(files) {
        if (!allowUploads) {
            showToast('Uploads are disabled', true);
            return;
        }
        for (const f of files) uploadFile(f);
    }

    ['dragenter', 'dragover'].forEach(ev => {
        dropzone.addEventListener(ev, (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropzone.classList.add('drag');
        });
    });

    ['dragleave', 'drop'].forEach(ev => {
        dropzone.addEventListener(ev, (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (ev === 'dragleave' && e.target !== dropzone) return;
            dropzone.classList.remove('drag');
        });
    });

    dropzone.addEventListener('drop', (e) => {
        const files = e.dataTransfer && e.dataTransfer.files;
        if (files && files.length) uploadFiles(files);
    });

    fileInput.addEventListener('change', () => {
        if (fileInput.files.length) uploadFiles(fileInput.files);
        fileInput.value = '';
    });

    refreshBtn.addEventListener('click', loadFiles);

    window.addEventListener('paste', (e) => {
        const items = e.clipboardData && e.clipboardData.files;
        if (items && items.length) uploadFiles(items);
    });

    bootstrap();
    setInterval(() => {
        if (loginView.classList.contains('hidden')) loadFiles();
    }, 15000);
})();
