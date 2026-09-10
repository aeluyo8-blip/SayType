import http from 'node:http';
import { WebSocketServer } from 'ws';
import { injectText, listLanIPv4, randomPin } from './inject.mjs';

const PORT = Number(process.env.PHONE_TYPE_PORT || 8787);
const HOST = process.env.PHONE_TYPE_HOST || '0.0.0.0';
const PIN = process.env.PHONE_TYPE_PIN || randomPin();

const server = http.createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('phone-type PC service. Use WebSocket.\n');
});

const wss = new WebSocketServer({ server });

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
        const r = await injectText(text);
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

server.listen(PORT, HOST, () => {
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
});

function shutdown() {
  log('shutting down');
  wss.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 500).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
