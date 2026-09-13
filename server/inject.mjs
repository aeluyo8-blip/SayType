import { spawn } from 'node:child_process';
import { randomInt } from 'node:crypto';
import os from 'node:os';

// A stuck powershell (clipboard locked by another app, STA hang) must not
// block the inject queue forever, so every spawn gets a hard timeout.
const PS_TIMEOUT_MS = Number(process.env.SAYTYPE_PS_TIMEOUT || 5000);

function runPs(script) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
      {
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: process.env,
      }
    );
    let out = '';
    let err = '';
    let settled = false;
    const finish = (fn) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn();
    };
    const timer = setTimeout(() => {
      finish(() => {
        child.kill();
        reject(Object.assign(new Error(`powershell timeout after ${PS_TIMEOUT_MS}ms`), { code: 'inject_failed' }));
      });
    }, PS_TIMEOUT_MS);
    child.stdout.on('data', (d) => {
      out += d.toString();
    });
    child.stderr.on('data', (d) => {
      err += d.toString();
    });
    child.on('error', (e) => finish(() => reject(e)));
    child.on('close', (code) => {
      finish(() => {
        if (code === 0) resolve(out.trim());
        else reject(new Error(err.trim() || out.trim() || `powershell exit ${code}`));
      });
    });
  });
}

/**
 * Inject text at current cursor via clipboard + Ctrl+V.
 * @param {string} text
 * @returns {Promise<{ok: true, method: 'clipboard'}>}
 */
export async function injectText(text) {
  if (typeof text !== 'string' || text.length === 0) {
    throw Object.assign(new Error('empty text'), { code: 'empty_text' });
  }
  if (text.length > 20000) {
    throw Object.assign(new Error('text too long'), { code: 'too_long' });
  }

  // UTF-8 via base64 avoids PowerShell quoting/encoding issues for CJK
  const b64 = Buffer.from(text, 'utf8').toString('base64');
  const script = `
$ErrorActionPreference = 'Stop'
$bytes = [System.Convert]::FromBase64String('${b64}')
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
Add-Type -AssemblyName System.Windows.Forms
$ok = $false
for ($i = 0; $i -lt 3 -and -not $ok; $i++) {
  try { [System.Windows.Forms.Clipboard]::SetText($text); $ok = $true }
  catch { Start-Sleep -Milliseconds 120 }
}
if (-not $ok) { throw 'clipboard busy' }
Start-Sleep -Milliseconds 40
[System.Windows.Forms.SendKeys]::SendWait('^v')
Write-Output 'OK'
`.trim();

  const out = await runPs(script);
  if (!out.includes('OK')) {
    throw Object.assign(new Error(out || 'inject failed'), { code: 'inject_failed' });
  }
  return { ok: true, method: 'clipboard' };
}

export function listLanIPv4() {
  const nets = os.networkInterfaces();
  const addrs = [];
  for (const list of Object.values(nets)) {
    for (const ni of list || []) {
      // 169.254.* 是未联网时的链路本地地址，小白拿到手也连不通，直接过滤
      if (ni.family === 'IPv4' && !ni.internal && !ni.address.startsWith('169.254.')) {
        addrs.push(ni.address);
      }
    }
  }
  return addrs;
}

export function randomPin() {
  return String(randomInt(100000, 1000000));
}
