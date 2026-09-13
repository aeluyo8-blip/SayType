/**
 * End-to-end PC verification:
 * 1. Start server with fixed PIN
 * 2. Open a blank temp file in Notepad and focus it
 * 3. Connect via WS, send Chinese text
 * 4. Ctrl+S save, read the file from disk
 *
 * Usage: node scripts/verify-pc.mjs
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const PIN = '246810';
const PORT = 8790;
const TEXT = `验证成功-中文注入-${Date.now()}`;
const FILE = path.join(os.tmpdir(), `SayType-verify-${Date.now()}.txt`);

function ps(script) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-Command', script],
      {
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
      }
    );
    let out = Buffer.alloc(0);
    let err = Buffer.alloc(0);
    child.stdout.on('data', (d) => {
      out = Buffer.concat([out, d]);
    });
    child.stderr.on('data', (d) => {
      err = Buffer.concat([err, d]);
    });
    child.on('close', (code) => {
      if (code === 0) resolve(out.toString('utf8').trim());
      else reject(new Error(err.toString('utf8') || out.toString('utf8')));
    });
  });
}

async function waitFor(fn, ms = 5000, step = 100) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const v = await fn();
    if (v) return v;
    await delay(step);
  }
  throw new Error('timeout waiting');
}

function activatePid(pid) {
  return ps(`
$sig = @'
using System;
using System.Runtime.InteropServices;
public class WinFocus {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int n);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
'@
Add-Type -TypeDefinition $sig -ErrorAction SilentlyContinue
$p = Get-Process -Id ${pid} -ErrorAction SilentlyContinue
if (-not $p) { Write-Error 'no process'; exit 1 }
[WinFocus]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
[WinFocus]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 300
$fg = [WinFocus]::GetForegroundWindow()
if ($fg -eq $p.MainWindowHandle) { Write-Output 'FOCUSED' } else { Write-Output "UNFOCUSED $fg vs $($p.MainWindowHandle)" }
`);
}

const server = spawn(process.execPath, ['server/index.mjs'], {
  cwd: ROOT,
  env: { ...process.env, SAYTYPE_PORT: String(PORT), SAYTYPE_PIN: PIN },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let serverLog = '';
server.stdout.on('data', (d) => {
  serverLog += d.toString();
});
server.stderr.on('data', (d) => {
  serverLog += d.toString();
});

let notepadPid = null;

try {
  fs.writeFileSync(FILE, '', 'utf8');
  await waitFor(() => serverLog.includes('PIN'), 5000);

  const pidOut = await ps(`
    $np = Start-Process notepad -ArgumentList '${FILE.replace(/'/g, "''")}' -PassThru
    Start-Sleep -Milliseconds 1000
    Write-Output $np.Id
  `);
  notepadPid = Number(pidOut.split(/\s+/).pop());
  if (!notepadPid) throw new Error(`bad notepad pid: ${pidOut}`);

  const focus = await activatePid(notepadPid);
  if (!focus.includes('FOCUSED')) {
    // retry once
    await delay(400);
    const focus2 = await activatePid(notepadPid);
    if (!focus2.includes('FOCUSED')) {
      throw new Error(`notepad not focused: ${focus2}`);
    }
  }

  const ack = await new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
    const timer = setTimeout(() => reject(new Error('ws timeout')), 8000);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'hello', pin: PIN })));
    ws.on('message', (raw) => {
      const msg = JSON.parse(String(raw));
      if (msg.type === 'welcome') {
        ws.send(JSON.stringify({ type: 'text', text: TEXT, seq: 1 }));
      } else if (msg.type === 'ack') {
        clearTimeout(timer);
        ws.close();
        resolve(msg);
      } else if (msg.type === 'error') {
        clearTimeout(timer);
        ws.close();
        reject(new Error(`server error ${msg.code}`));
      }
    });
    ws.on('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });

  if (!ack.ok) throw new Error('ack not ok');

  await delay(300);
  await activatePid(notepadPid);
  await ps(`
    Add-Type -AssemblyName System.Windows.Forms
    Start-Sleep -Milliseconds 100
    [System.Windows.Forms.SendKeys]::SendWait('^s')
    Start-Sleep -Milliseconds 400
    Write-Output 'SAVED'
  `);

  await waitFor(() => {
    if (!fs.existsSync(FILE)) return false;
    const content = fs.readFileSync(FILE, 'utf8');
    return content.length > 0 ? content : false;
  }, 4000, 150);

  const content = fs.readFileSync(FILE, 'utf8');
  const matched = content.includes(TEXT);

  console.log(
    JSON.stringify(
      {
        ack,
        file: FILE,
        contentLen: content.length,
        matched,
        content,
      },
      null,
      2
    )
  );

  if (!matched) throw new Error('file content mismatch');
  console.log('VERIFY_PC_PASS');
  process.exitCode = 0;
} catch (e) {
  console.error('VERIFY_PC_FAIL', e.message);
  console.error('--- server log ---');
  console.error(serverLog.slice(0, 1500));
  process.exitCode = 1;
} finally {
  server.kill();
  try {
    if (notepadPid) {
      await ps(`Stop-Process -Id ${notepadPid} -Force -ErrorAction SilentlyContinue`);
    } else {
      await ps('Stop-Process -Name notepad -Force -ErrorAction SilentlyContinue');
    }
  } catch {
    // ignore
  }
  try {
    fs.unlinkSync(FILE);
  } catch {
    // ignore
  }
}
