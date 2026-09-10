import { spawn } from 'node:child_process';
import os from 'node:os';

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
    child.stdout.on('data', (d) => {
      out += d.toString();
    });
    child.stderr.on('data', (d) => {
      err += d.toString();
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) resolve(out.trim());
      else reject(new Error(err.trim() || out.trim() || `powershell exit ${code}`));
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
[System.Windows.Forms.Clipboard]::SetText($text)
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
      if (ni.family === 'IPv4' && !ni.internal) {
        addrs.push(ni.address);
      }
    }
  }
  return addrs;
}

export function randomPin() {
  return String(Math.floor(100000 + Math.random() * 900000));
}
