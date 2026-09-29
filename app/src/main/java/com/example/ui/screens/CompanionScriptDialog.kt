package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800

val PC_SERVER_PYTHON_SCRIPT = """
# ==============================================================================
# PC Receiver & Sender Script (FastAPI HTTP + pyftpdlib FTP + Phone Sender CLI)
# Run on your PC: python server.py
# Requirements: pip install fastapi uvicorn requests python-multipart pyftpdlib
# ==============================================================================

import os
import shutil
import threading
import requests
from fastapi import FastAPI, UploadFile, File
import uvicorn
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

UPLOAD_DIR = os.path.expanduser("~/Downloads/MediaSync")
os.makedirs(UPLOAD_DIR, exist_ok=True)

app = FastAPI(title="MediaSync PC Bridge")

@app.get("/")
@app.get("/health")
def health():
    return {"status": "online", "service": "MediaSync PC Bridge", "upload_dir": UPLOAD_DIR}

@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    target_path = os.path.join(UPLOAD_DIR, file.filename)
    with open(target_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
    size_mb = os.path.getsize(target_path) / (1024 * 1024)
    print(f"📥 [PC Received] {file.filename} ({size_mb:.2f} MB)")
    return {"status": "saved", "filename": file.filename, "size_bytes": os.path.getsize(target_path)}

def send_file_to_phone(phone_ip: str, file_path: str, port: int = 8080):
    \"\"\"Send any file from PC to Android Phone's Embedded Receiver\"\"\"
    if not os.path.exists(file_path):
        print(f"❌ File not found: {file_path}")
        return
    url = f"http://{phone_ip}:{port}/receive"
    print(f"📤 Sending {file_path} to Android Phone at {url}...")
    with open(file_path, "rb") as f:
        files = {"file": (os.path.basename(file_path), f)}
        try:
            resp = requests.post(url, files=files, timeout=60)
            print(f"✅ Response from Phone: {resp.status_code} - {resp.text}")
        except Exception as e:
            print(f"❌ Transfer error: {e}")

def discover_phone(subnet_prefix="192.168.1", port=8080):
    \"\"\"Discover Android Phone running MediaSync Bridge on LAN\"\"\"
    print("🔍 Discovering phone on LAN...")
    for i in range(1, 255):
        ip = f"{subnet_prefix}.{i}"
        try:
            r = requests.get(f"http://{ip}:{port}/ping", timeout=0.15)
            if r.status_code == 200:
                data = r.json()
                print(f"✨ Discovered Android Phone at {ip}: {data}")
                return ip
        except Exception:
            pass
    return None

def start_ftp_server():
    authorizer = DummyAuthorizer()
    authorizer.add_user("user", "password", UPLOAD_DIR, perm="elradfmwMT")
    handler = FTPHandler
    handler.authorizer = authorizer
    handler.banner = "MediaSync FTP Ready"
    server = FTPServer(("0.0.0.0", 2121), handler)
    print("🚀 FTP Server running on 0.0.0.0:2121")
    server.serve_forever()

if __name__ == "__main__":
    ftp_thread = threading.Thread(target=start_ftp_server, daemon=True)
    ftp_thread.start()

    print("🚀 FastAPI HTTP Server running on http://0.0.0.0:8000")
    print(f"📁 Files from phone saved in: {UPLOAD_DIR}")
    print("💡 To send a file to phone: python -c 'import server; server.send_file_to_phone(\"<PHONE_IP>\", \"myfile.jpg\")'")
    uvicorn.run(app, host="0.0.0.0", port=8000)
""".trimIndent()

@Composable
fun CompanionScriptDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("pc_script_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = CyanGlow,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PC Companion Script",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Slate400
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Save this script as `server.py` on your PC. It receives files on port 8000 & FTP 2121, and includes the helper function to push files back to the phone on port 8080 (`/receive` and `/ping`).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Code Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF070B14))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = PC_SERVER_PYTHON_SCRIPT,
                            color = CyanGlow,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("PC Server Script", PC_SERVER_PYTHON_SCRIPT)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied Python script to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = Color(0xFF0B1120)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("copy_script_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Python Script", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
