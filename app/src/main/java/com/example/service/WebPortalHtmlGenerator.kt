package com.example.service

import android.os.Build

object WebPortalHtmlGenerator {

    fun generateHtml(
        deviceModel: String,
        serverIp: String,
        port: Int,
        sharedFiles: List<SharedFileItem>,
        isSecurityEnabled: Boolean = true,
        authToken: String = "",
        isPreAuthenticated: Boolean = false
    ): String {
        val filesHtml = if (sharedFiles.isEmpty()) {
            """<div class="empty-state">No documents or media shared yet. Select files in the MediaSync Android app to make them downloadable here.</div>"""
        } else {
            sharedFiles.joinToString("\n") { file ->
                val icon = when {
                    file.isApk -> "📦"
                    file.mimeType.startsWith("image/") -> "🖼️"
                    file.mimeType.startsWith("video/") -> "🎬"
                    file.mimeType.startsWith("audio/") -> "🎵"
                    file.mimeType.contains("pdf") -> "📄"
                    else -> "📁"
                }
                """
                <div class="file-item">
                    <div class="file-icon">$icon</div>
                    <div class="file-info">
                        <div class="file-name" title="${file.name}">${file.name}</div>
                        <div class="file-meta">${file.formattedSize} &bull; ${file.mimeType.take(28)}</div>
                    </div>
                    <a href="/download/file?id=${file.id}" class="btn-download auth-download" data-id="${file.id}" download="${file.name}">
                        Download
                    </a>
                </div>
                """.trimIndent()
            }
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>MediaSync &bull; $deviceModel</title>
    <style>
        :root {
            --primary: #0284c7;
            --primary-hover: #0369a1;
            --accent: #10b981;
            --bg: #f8fafc;
            --surface: #ffffff;
            --border: #e2e8f0;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --radius: 16px;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background: var(--bg); color: var(--text-main); line-height: 1.5; padding: 20px 12px; }
        .container { max-width: 680px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
        
        .header-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: var(--radius);
            padding: 20px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.03);
            display: flex;
            align-items: center;
            justify-content: space-between;
        }
        .header-left { display: flex; align-items: center; gap: 14px; }
        .device-badge {
            width: 48px; height: 48px; border-radius: 12px;
            background: #e0f2fe; color: var(--primary);
            display: flex; align-items: center; justify-content: center; font-size: 24px;
        }
        .device-title { font-size: 18px; font-weight: 700; color: var(--text-main); }
        .device-sub { font-size: 13px; color: var(--text-muted); }
        .status-pill {
            display: inline-flex; align-items: center; gap: 6px;
            background: #dcfce7; color: #15803d;
            padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600;
        }
        .status-dot { width: 8px; height: 8px; border-radius: 50%; background: #16a34a; }

        .security-badge {
            display: inline-flex; align-items: center; gap: 6px;
            background: #f0fdf4; color: #166534; border: 1px solid #bbf7d0;
            padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; margin-top: 3px;
        }

        .apk-banner {
            background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);
            color: #ffffff;
            border-radius: var(--radius);
            padding: 20px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            box-shadow: 0 6px 16px rgba(2, 132, 199, 0.25);
        }
        .apk-banner h3 { font-size: 17px; margin-bottom: 4px; }
        .apk-banner p { font-size: 13px; opacity: 0.9; }
        .btn-apk {
            background: #ffffff; color: var(--primary);
            font-weight: 700; text-decoration: none;
            padding: 10px 18px; border-radius: 10px; font-size: 14px;
            box-shadow: 0 2px 6px rgba(0,0,0,0.1); white-space: nowrap;
        }
        .btn-apk:active { transform: scale(0.97); }

        .card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: var(--radius);
            padding: 20px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.03);
        }
        .card-header {
            display: flex; align-items: center; justify-content: space-between;
            margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--border);
        }
        .card-title { font-size: 16px; font-weight: 700; display: flex; align-items: center; gap: 8px; }

        .file-list { display: flex; flex-direction: column; gap: 10px; }
        .file-item {
            display: flex; align-items: center; justify-content: space-between;
            padding: 12px 14px; background: #f8fafc; border: 1px solid var(--border);
            border-radius: 12px; gap: 12px;
        }
        .file-icon { font-size: 24px; min-width: 32px; text-align: center; }
        .file-info { flex: 1; min-width: 0; }
        .file-name { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .file-meta { font-size: 12px; color: var(--text-muted); }
        .btn-download {
            background: var(--primary); color: #ffffff;
            text-decoration: none; padding: 7px 14px; border-radius: 8px;
            font-size: 12px; font-weight: 600; white-space: nowrap;
        }

        .upload-area {
            border: 2px dashed #cbd5e1; border-radius: 12px;
            padding: 30px 16px; text-align: center; cursor: pointer;
            background: #f8fafc; transition: all 0.2s;
        }
        .upload-area:hover, .upload-area.dragover { border-color: var(--primary); background: #f0f9ff; }
        .upload-icon { font-size: 36px; margin-bottom: 8px; }
        .upload-text { font-size: 14px; font-weight: 600; color: var(--text-main); }
        .upload-subtext { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
        
        #fileInput { display: none; }
        .progress-box { margin-top: 14px; display: none; }
        .progress-bar-bg { width: 100%; height: 8px; background: #e2e8f0; border-radius: 4px; overflow: hidden; }
        .progress-bar-fill { height: 100%; width: 0%; background: var(--accent); transition: width 0.15s; }
        .progress-text { font-size: 12px; color: var(--text-muted); margin-top: 6px; display: flex; justify-content: space-between; }
        
        .empty-state { text-align: center; padding: 24px; color: var(--text-muted); font-size: 13px; }
        .toast {
            position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%);
            background: #1e293b; color: #fff; padding: 10px 20px; border-radius: 30px;
            font-size: 13px; font-weight: 500; opacity: 0; transition: opacity 0.3s;
            pointer-events: none; z-index: 999;
        }
        .toast.show { opacity: 1; }

        /* Security PIN Screen */
        .pin-overlay {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: var(--radius);
            padding: 36px 20px;
            text-align: center;
            box-shadow: 0 10px 25px rgba(0,0,0,0.05);
            margin: 40px auto;
            max-width: 440px;
        }
        .lock-icon { font-size: 48px; margin-bottom: 12px; }
        .pin-title { font-size: 20px; font-weight: 700; color: var(--text-main); margin-bottom: 6px; }
        .pin-sub { font-size: 13px; color: var(--text-muted); margin-bottom: 24px; }
        .pin-input {
            width: 100%; max-width: 280px; padding: 12px; font-size: 24px; text-align: center;
            letter-spacing: 6px; font-weight: 700; border: 2px solid var(--border);
            border-radius: 12px; outline: none; margin-bottom: 16px; background: #f8fafc;
            font-family: monospace;
        }
        .pin-input:focus { border-color: var(--primary); background: #fff; }
        .btn-unlock {
            background: var(--primary); color: #fff; border: none; padding: 12px 28px;
            border-radius: 12px; font-size: 15px; font-weight: 700; cursor: pointer;
            width: 100%; max-width: 280px; transition: background 0.2s;
        }
        .btn-unlock:hover { background: var(--primary-hover); }
        .pin-hint { font-size: 12px; color: var(--text-muted); margin-top: 14px; }
    </style>
</head>
<body>
    <!-- PIN Security Screen (Shown if PIN is required and not yet verified) -->
    <div id="pinScreen" class="pin-overlay" style="display: none;">
        <div class="lock-icon">🔒</div>
        <div class="pin-title">Security Verification</div>
        <div class="pin-sub">Enter the 6-digit PIN shown in the MediaSync app on <strong>$deviceModel</strong> to access files.</div>
        <input type="text" id="pinInput" class="pin-input" maxlength="6" pattern="[0-9]*" placeholder="••••••" autofocus onkeyup="if(event.key==='Enter') submitPin()">
        <br>
        <button class="btn-unlock" onclick="submitPin()">Unlock Access</button>
        <div class="pin-hint">🛡️ Your connection is protected against unauthorized local access.</div>
    </div>

    <div class="container" id="mainContent" style="display: block;">
        <!-- Device Header -->
        <div class="header-card">
            <div class="header-left">
                <div class="device-badge">📱</div>
                <div>
                    <div class="device-title">MediaSync Android Hub</div>
                    <div class="device-sub">$deviceModel &bull; $serverIp</div>
                    <div class="security-badge">🛡️ PIN Protected Session</div>
                </div>
            </div>
            <div class="status-pill">
                <span class="status-dot"></span>
                <span>Active</span>
            </div>
        </div>

        <!-- Android APK Download Banner -->
        <div class="apk-banner">
            <div>
                <h3>Android User? Get the Latest App</h3>
                <p>Install live MediaSync APK directly with all recent updates</p>
            </div>
            <a href="/download/latest-apk" id="apkDownloadBtn" class="btn-apk" download="MediaSync.apk">Download Latest APK</a>
        </div>

        <!-- Shared Files (Download from Android) -->
        <div class="card">
            <div class="card-header">
                <div class="card-title">
                    <span>📥</span>
                    <span>Download Files from Android</span>
                </div>
                <button onclick="refreshFileList()" style="background:none;border:none;color:var(--primary);cursor:pointer;font-size:12px;font-weight:600;">Refresh</button>
            </div>
            <div class="file-list" id="fileList">
                $filesHtml
            </div>
        </div>

        <!-- Send Files to Android (Upload from iOS / PC) -->
        <div class="card">
            <div class="card-header">
                <div class="card-title">
                    <span>📤</span>
                    <span>Send Files to Android Device</span>
                </div>
            </div>
            <div class="upload-area" id="dropArea" onclick="document.getElementById('fileInput').click()">
                <div class="upload-icon">📁</div>
                <div class="upload-text">Choose files from iPhone / PC / Mac</div>
                <div class="upload-subtext">Tap to browse Photos, Videos, or Documents</div>
                <input type="file" id="fileInput" multiple onchange="handleFileSelect(this.files)">
            </div>
            <div class="progress-box" id="progressBox">
                <div class="progress-bar-bg">
                    <div class="progress-bar-fill" id="progressFill"></div>
                </div>
                <div class="progress-text">
                    <span id="progressStatus">Uploading...</span>
                    <span id="progressPercent">0%</span>
                </div>
            </div>
        </div>

        <div style="text-align:center;font-size:12px;color:var(--text-muted);margin-top:10px;">
            Secure Peer-to-Peer Transfer &bull; MediaSync
        </div>
    </div>

    <div class="toast" id="toast"></div>

    <script>
        const IS_SECURITY_ENABLED = ${isSecurityEnabled};
        const SERVER_AUTH_TOKEN = "${if (isPreAuthenticated) authToken else ""}";

        let currentAuthToken = sessionStorage.getItem('mediasync_auth_token') || "";

        // Check if token was passed in query parameter
        const urlParams = new URLSearchParams(window.location.search);
        const queryToken = urlParams.get('token') || urlParams.get('pin');
        if (queryToken) {
            currentAuthToken = queryToken;
            sessionStorage.setItem('mediasync_auth_token', queryToken);
        } else if (SERVER_AUTH_TOKEN) {
            currentAuthToken = SERVER_AUTH_TOKEN;
            sessionStorage.setItem('mediasync_auth_token', SERVER_AUTH_TOKEN);
        }

        function initAuth() {
            if (!IS_SECURITY_ENABLED) {
                showMainContent();
                return;
            }

            if (currentAuthToken) {
                verifyToken(currentAuthToken);
            } else {
                showPinScreen();
            }
        }

        function showPinScreen() {
            document.getElementById('pinScreen').style.display = 'block';
            document.getElementById('mainContent').style.display = 'none';
        }

        function showMainContent() {
            document.getElementById('pinScreen').style.display = 'none';
            document.getElementById('mainContent').style.display = 'block';
            updateDownloadLinks();
        }

        async function submitPin() {
            const pin = document.getElementById('pinInput').value.trim();
            if (!pin || pin.length < 4) {
                showToast('Please enter the 6-digit PIN');
                return;
            }

            try {
                const res = await fetch('/api/auth?pin=' + encodeURIComponent(pin));
                const data = await res.json();
                if (data && data.authenticated) {
                    currentAuthToken = data.token || pin;
                    sessionStorage.setItem('mediasync_auth_token', currentAuthToken);
                    showToast('✓ PIN Verified Successfully');
                    showMainContent();
                    refreshFileList();
                } else {
                    showToast('❌ Incorrect PIN. Please check your phone screen.');
                }
            } catch (e) {
                showToast('Verification error: ' + e.message);
            }
        }

        async function verifyToken(token) {
            try {
                const res = await fetch('/api/auth?token=' + encodeURIComponent(token));
                const data = await res.json();
                if (data && data.authenticated) {
                    showMainContent();
                } else {
                    sessionStorage.removeItem('mediasync_auth_token');
                    currentAuthToken = "";
                    showPinScreen();
                }
            } catch (e) {
                showMainContent();
            }
        }

        function updateDownloadLinks() {
            const apkBtn = document.getElementById('apkDownloadBtn');
            if (apkBtn && currentAuthToken) {
                apkBtn.href = '/download/latest-apk?token=' + encodeURIComponent(currentAuthToken);
            }
            document.querySelectorAll('.auth-download').forEach(a => {
                const id = a.getAttribute('data-id');
                if (id) {
                    a.href = '/download/file?id=' + encodeURIComponent(id) + (currentAuthToken ? '&token=' + encodeURIComponent(currentAuthToken) : '');
                }
            });
        }

        function showToast(msg) {
            const toast = document.getElementById('toast');
            toast.innerText = msg;
            toast.classList.add('show');
            setTimeout(() => toast.classList.remove('show'), 3500);
        }

        const dropArea = document.getElementById('dropArea');
        ['dragenter', 'dragover'].forEach(name => {
            dropArea.addEventListener(name, (e) => { e.preventDefault(); dropArea.classList.add('dragover'); }, false);
        });
        ['dragleave', 'drop'].forEach(name => {
            dropArea.addEventListener(name, (e) => { e.preventDefault(); dropArea.classList.remove('dragover'); }, false);
        });
        dropArea.addEventListener('drop', (e) => {
            const dt = e.dataTransfer;
            const files = dt.files;
            handleFileSelect(files);
        });

        function handleFileSelect(files) {
            if (!files || files.length === 0) return;
            uploadFiles(Array.from(files));
        }

        async function uploadFiles(files) {
            const progressBox = document.getElementById('progressBox');
            const progressFill = document.getElementById('progressFill');
            const progressStatus = document.getElementById('progressStatus');
            const progressPercent = document.getElementById('progressPercent');

            progressBox.style.display = 'block';

            for (let i = 0; i < files.length; i++) {
                const file = files[i];
                progressStatus.innerText = 'Uploading ' + (i+1) + ' of ' + files.length + ': ' + file.name;
                
                try {
                    await uploadSingleFile(file, (percent) => {
                        progressFill.style.width = percent + '%';
                        progressPercent.innerText = Math.round(percent) + '%';
                    });
                } catch (e) {
                    showToast('Failed to upload ' + file.name + ': ' + e.message);
                    progressBox.style.display = 'none';
                    return;
                }
            }

            progressStatus.innerText = 'All ' + files.length + ' file(s) saved to phone!';
            progressPercent.innerText = '100%';
            showToast('✓ Successfully sent to Android Downloads/MediaSync!');
            setTimeout(() => { progressBox.style.display = 'none'; }, 4000);
            document.getElementById('fileInput').value = '';
        }

        function uploadSingleFile(file, onProgress) {
            return new Promise((resolve, reject) => {
                const xhr = new XMLHttpRequest();
                const uploadUrl = '/receive' + (currentAuthToken ? '?token=' + encodeURIComponent(currentAuthToken) : '');
                xhr.open('POST', uploadUrl, true);
                if (currentAuthToken) {
                    xhr.setRequestHeader('X-Auth-Token', currentAuthToken);
                }
                
                const formData = new FormData();
                formData.append('file', file, file.name);

                xhr.upload.onprogress = (e) => {
                    if (e.lengthComputable) {
                        const percent = (e.loaded / e.total) * 100;
                        onProgress(percent);
                    }
                };

                xhr.onload = () => {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        resolve(xhr.responseText);
                    } else if (xhr.status === 401) {
                        showPinScreen();
                        reject(new Error('Security PIN verification required'));
                    } else {
                        reject(new Error('Server responded with ' + xhr.status));
                    }
                };

                xhr.onerror = () => reject(new Error('Network connection error'));
                xhr.send(formData);
            });
        }

        async function refreshFileList() {
            try {
                const url = '/api/shared' + (currentAuthToken ? '?token=' + encodeURIComponent(currentAuthToken) : '');
                const res = await fetch(url, {
                    headers: currentAuthToken ? { 'X-Auth-Token': currentAuthToken } : {}
                });
                if (res.ok) {
                    const data = await res.json();
                    renderFiles(data.files || []);
                    showToast('Updated file list');
                } else if (res.status === 401) {
                    showPinScreen();
                }
            } catch (e) {}
        }

        function renderFiles(files) {
            const list = document.getElementById('fileList');
            if (!files || files.length === 0) {
                list.innerHTML = '<div class="empty-state">No documents or media shared yet.</div>';
                return;
            }
            list.innerHTML = files.map(f => `
                <div class="file-item">
                    <div class="file-icon">${'$'}{f.is_apk ? '📦' : (f.mime.startsWith('image/') ? '🖼️' : '📄')}</div>
                    <div class="file-info">
                        <div class="file-name" title="${'$'}{f.name}">${'$'}{f.name}</div>
                        <div class="file-meta">${'$'}{f.size} &bull; ${'$'}{f.mime.substring(0,25)}</div>
                    </div>
                    <a href="/download/file?id=${'$'}{f.id}${'$'}{currentAuthToken ? '&token=' + encodeURIComponent(currentAuthToken) : ''}" class="btn-download auth-download" data-id="${'$'}{f.id}" download="${'$'}{f.name}">Download</a>
                </div>
            `).join('');
        }

        // Auto refresh files list every 8 seconds if authenticated
        setInterval(() => {
            if (currentAuthToken || !IS_SECURITY_ENABLED) {
                refreshFileList();
            }
        }, 8000);

        initAuth();
    </script>
</body>
</html>
        """.trimIndent()
    }
}
