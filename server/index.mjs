import http from 'node:http';
import { WebSocketServer } from 'ws';
import QRCode from 'qrcode';
import { injectText, listLanIPv4, randomPin } from './inject.mjs';

const PORT = Number(process.env.PHONE_TYPE_PORT || 8787);
const HOST = process.env.PHONE_TYPE_HOST || '0.0.0.0';
const PIN = process.env.PHONE_TYPE_PIN || randomPin();

const server = http.createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('phone-type PC service. Use WebSocket.\n');
});

// Register before WebSocketServer: ws's own 'error' listener throws first and
// breaks the emit chain, so a late handler never sees EADDRINUSE.
server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error(`端口 ${PORT} 已被占用：可能已有一个 phone-type 在运行，或用 PHONE_TYPE_PORT=xxxx 换端口。`);
    process.exit(1);
  }
  console.error(`server error: ${e.message}`);
  process.exit(1);
});

const wss = new WebSocketServer({ server });

// Heartbeat: phones vanishing behind NAT / killed apps leave half-open TCP
// connections that never emit 'close'. Ping every 30s, terminate on 2 misses.
const HEARTBEAT_MS = Number(process.env.PHONE_TYPE_HEARTBEAT_MS || 30000);
const heartbeatTimer = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      log('dead connection terminated');
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_MS);

// Serialize clipboard+Ctrl+V so concurrent text frames cannot interleave.
let injectChain = Promise.resolve();
function enqueueInject(text) {
  const run = injectChain.then(() => injectText(text));
  injectChain = run.then(
    () => undefined,
    () => undefined
  );
  return run;
}

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function log(msg) {
  const ts = new Date().toISOString().slice(11, 19);
  console.log(`[${ts}] ${msg}`);
}

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress || '?';
  let authed = false;
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  log(`client connected ${ip}`);

  ws.on('message', async (raw) => {
    let msg;
    try {
      msg = JSON.parse(String(raw));
    } catch {
      send(ws, { type: 'error', code: 'bad_json' });
      return;
    }

    if (msg.type === 'hello') {
      if (String(msg.pin ?? '') === PIN) {
        authed = true;
        send(ws, { type: 'welcome', server: 'phone-type', ok: true });
        log(`auth ok ${ip}`);
      } else {
        send(ws, { type: 'error', code: 'bad_pin' });
        log(`auth fail ${ip}`);
        ws.close(4001, 'bad_pin');
      }
      return;
    }

    if (msg.type === 'ping') {
      send(ws, { type: 'pong' });
      return;
    }

    if (!authed) {
      send(ws, { type: 'error', code: 'not_hello' });
      return;
    }

    if (msg.type === 'text') {
      const text = typeof msg.text === 'string' ? msg.text : '';
      const seq = msg.seq ?? null;
      if (!text) {
        send(ws, { type: 'error', code: 'empty_text', seq });
        return;
      }
      log(`text seq=${seq ?? '-'} len=${text.length}`);
      try {
        const r = await enqueueInject(text);
        send(ws, { type: 'ack', seq, ok: true, method: r.method });
      } catch (e) {
        const code = e.code === 'inject_failed' || e.code === 'empty_text' || e.code === 'too_long'
          ? e.code
          : 'inject_failed';
        send(ws, { type: 'error', code, seq });
        log(`inject fail code=${code} detail=${e.message?.slice(0, 80)}`);
      }
      return;
    }

    send(ws, { type: 'error', code: 'unknown_type' });
  });

  ws.on('close', () => log(`client closed ${ip}`));
  ws.on('error', () => log(`client error ${ip}`));
});

server.listen(PORT, HOST, async () => {
  const addrs = listLanIPv4();
  console.log('========================================');
  console.log('  phone-type PC service');
  console.log('========================================');
  console.log(`  port : ${PORT}`);
  console.log(`  PIN  : ${PIN}`);
  if (addrs.length) {
    console.log('  LAN  :');
    for (const a of addrs) console.log(`         ws://${a}:${PORT}`);
  } else {
    console.log('  LAN  : (no non-internal IPv4 found)');
  }
  console.log('----------------------------------------');
  console.log('  Focus a text field on Windows,');
  console.log('  connect from the Android app, send.');
  console.log('========================================');

  // 后台模式：供启动脚本/「显示 PIN」读取，不打印用户输入正文
  const statusFile = process.env.PHONE_TYPE_STATUS_FILE;
  if (statusFile) {
    try {
      const { writeFileSync, mkdirSync } = await import('node:fs');
      const { dirname } = await import('node:path');
      mkdirSync(dirname(statusFile), { recursive: true });
      writeFileSync(
        statusFile,
        JSON.stringify(
          {
            pid: process.pid,
            port: PORT,
            pin: PIN,
            addrs,
            startedAt: new Date().toISOString(),
          },
          null,
          2
        ),
        'utf8'
      );
    } catch (e) {
      console.error(`status file write failed: ${e.message}`);
    }
  }

  // 手机 APP 扫这个二维码即可自动填好 IP/端口/PIN
  if (addrs.length) {
    const configUri = `phonetype://${addrs[0]}:${PORT}?pin=${PIN}`;
    try {
      const qr = await QRCode.toString(configUri, { type: 'terminal', small: true });
      console.log('');
      console.log('  用手机 APP 的「扫码配置」对准下面的二维码：');
      console.log('');
      console.log(qr);
      console.log(`  （二维码内容：${configUri}）`);
      if (addrs.length > 1) {
        console.log(`  本机有多个网卡 IP，扫码连不上就改用手动填写：${addrs.join(', ')}`);
      }
    } catch (e) {
      console.log(`  (二维码生成失败: ${e.message}，请手动填写 IP 和 PIN)`);
    }
  }
});

function shutdown() {
  log('shutting down');
  clearInterval(heartbeatTimer);
  wss.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 500).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

// Keep the resident service alive on stray async errors; log and continue.
process.on('uncaughtException', (e) => log(`uncaughtException ${e?.stack || e}`));
process.on('unhandledRejection', (e) => log(`unhandledRejection ${e?.stack || e}`));
