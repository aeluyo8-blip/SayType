/**
 * Dev client: connect, hello, send one text.
 * Usage: node scripts/dev-client.mjs [host] [port] [pin] [text]
 * Env: SAYTYPE_PIN if args omitted for pin.
 */
import WebSocket from 'ws';

const host = process.argv[2] || '127.0.0.1';
const port = process.argv[3] || '8787';
const pin = process.argv[4] || process.env.SAYTYPE_PIN || '';
const text = process.argv[5] || `SayType 测试 ${new Date().toISOString()}`;

const url = `ws://${host}:${port}`;
const ws = new WebSocket(url);
let seq = 1;

const fail = (msg) => {
  console.error('FAIL', msg);
  process.exit(1);
};

ws.on('open', () => {
  console.log('open', url);
  ws.send(JSON.stringify({ type: 'hello', pin }));
});

ws.on('message', (raw) => {
  const msg = JSON.parse(String(raw));
  console.log('recv', msg);
  if (msg.type === 'welcome') {
    ws.send(JSON.stringify({ type: 'text', text, seq }));
  } else if (msg.type === 'ack' && msg.ok) {
    console.log('OK method=', msg.method, 'len=', text.length);
    ws.close();
    process.exit(0);
  } else if (msg.type === 'error') {
    fail(msg.code);
  }
});

ws.on('error', (e) => fail(e.message));
setTimeout(() => fail('timeout'), 8000);
