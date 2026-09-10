import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomPin, listLanIPv4 } from '../server/inject.mjs';

test('randomPin is 6 digits', () => {
  const p = randomPin();
  assert.match(p, /^\d{6}$/);
});

test('listLanIPv4 returns array of strings', () => {
  const addrs = listLanIPv4();
  assert.ok(Array.isArray(addrs));
  for (const a of addrs) assert.equal(typeof a, 'string');
});
