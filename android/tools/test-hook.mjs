/**
 * hook.js 검증 하네스.
 *
 * Android SDK 없이도 Phase 1 에서 가장 위험한 부분(후킹 스크립트)을 검증한다.
 * fetch / XMLHttpRequest / ttBridge 를 흉내낸 샌드박스에 실제 hook.js 를 로드해
 * 캡처 동작과 "페이지를 깨뜨리지 않는다"는 계약을 확인한다.
 *
 *   node android/tools/test-hook.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import vm from 'node:vm';

const HERE = dirname(fileURLToPath(import.meta.url));
const HOOK = readFileSync(join(HERE, '../app/src/main/assets/hook.js'), 'utf8');
const SEP = String.fromCharCode(31);

/** 실제 Response 처럼 1회 소비 규칙을 강제한다 — clone 순서 계약을 검증하기 위해. */
function makeResponse(body) {
  function create() {
    let used = false;
    return {
      get bodyUsed() { return used; },
      clone() {
        if (used) throw new TypeError('Response body is already used');
        return create();
      },
      async text() {
        if (used) throw new TypeError('Response body is already used');
        used = true;
        return body;
      },
    };
  }
  return create();
}

class NotTextError extends Error {}

/** hook.js 가 설치된 가짜 브라우저 하나를 만든다. */
function newBrowser({ routes = {}, installs = 1 } = {}) {
  const captured = [];
  const sandbox = {};
  sandbox.window = sandbox;
  sandbox.console = console;

  sandbox.ttBridge = { postMessage: (s) => captured.push(s) };

  sandbox.fetch = async function (input) {
    const url = typeof input === 'string' ? input : input.url;
    if (routes[url] === undefined) throw new Error('404 ' + url);
    return makeResponse(routes[url]);
  };

  sandbox.XMLHttpRequest = class {
    constructor() { this._listeners = {}; }
    open(method, url) { this._url = String(url); }
    addEventListener(type, fn) { (this._listeners[type] ||= []).push(fn); }
    send() {
      Object.defineProperty(this, 'responseText', {
        configurable: true,
        get: () => {
          if (this.responseType && this.responseType !== 'text') throw new NotTextError();
          return routes[this._url] ?? '{}';
        },
      });
      for (const fn of this._listeners.load ?? []) fn.call(this);
    }
  };

  const ctx = vm.createContext(sandbox);
  for (let i = 0; i < installs; i++) vm.runInContext(HOOK, ctx);
  return { sandbox, captured };
}

const parse = (raw) => {
  const first = raw.indexOf(SEP);
  const second = raw.indexOf(SEP, first + 1);
  return {
    url: raw.slice(0, first),
    at: Number(raw.slice(first + 1, second)),
    body: raw.slice(second + 1),
  };
};

const settle = () => new Promise((r) => setImmediate(r));

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

const FYP = '/api/recommend/item_list/?count=10&aid=1988';
const FYP_BODY = JSON.stringify({ itemList: [{ id: '1', stats: { diggCount: 12 } }, { id: '2' }] });
const DETAIL = '/api/item/detail/?itemId=7300000000000000000';
const DETAIL_BODY = JSON.stringify({ itemInfo: { itemStruct: { id: '7300000000000000000' } } });

test('FYP fetch 응답을 캡처하고 봉투가 정확히 파싱된다', async () => {
  const { sandbox, captured } = newBrowser({ routes: { [FYP]: FYP_BODY } });
  const before = Date.now();
  await sandbox.fetch(FYP);
  await settle();

  assert.equal(captured.length, 1);
  const { url, at, body } = parse(captured[0]);
  assert.equal(url, FYP);
  assert.ok(at >= before && at <= Date.now(), 'timestamp 가 호출 시각 범위 안이어야 한다');
  assert.equal(body, FYP_BODY);
  assert.equal(JSON.parse(body).itemList.length, 2);
});

test('관심 밖 엔드포인트는 캡처하지 않는다', async () => {
  const noise = '/api/commit/user/settings/';
  const { sandbox, captured } = newBrowser({ routes: { [noise]: '{"ok":1}' } });
  await sandbox.fetch(noise);
  await settle();
  assert.equal(captured.length, 0);
});

test('XHR 상세 응답을 캡처한다', async () => {
  const { sandbox, captured } = newBrowser({ routes: { [DETAIL]: DETAIL_BODY } });
  const xhr = new sandbox.XMLHttpRequest();
  xhr.open('GET', DETAIL);
  xhr.send();
  await settle();

  assert.equal(captured.length, 1);
  assert.equal(parse(captured[0]).body, DETAIL_BODY);
});

test('페이지는 여전히 본문을 읽을 수 있다 (clone 계약)', async () => {
  const { sandbox, captured } = newBrowser({ routes: { [FYP]: FYP_BODY } });
  const res = await sandbox.fetch(FYP);
  const pageSaw = await res.text();          // 페이지가 원본을 소비
  await settle();

  assert.equal(pageSaw, FYP_BODY, '후킹이 페이지의 본문을 가로채 먹으면 안 된다');
  assert.equal(captured.length, 1, '그러면서도 우리 사본은 남아야 한다');
  assert.equal(parse(captured[0]).body, FYP_BODY);
});

test('중복 설치해도 두 번 캡처하지 않는다', async () => {
  const { sandbox, captured } = newBrowser({ routes: { [FYP]: FYP_BODY }, installs: 3 });
  await sandbox.fetch(FYP);
  await settle();
  assert.equal(captured.length, 1);
});

test('본문에 이스케이프된 U+001F 가 있어도 봉투가 안 깨진다', async () => {
  const desc = 'ab' + String.fromCharCode(31) + 'cd';
  const tricky = JSON.stringify({ desc, itemList: [] });
  assert.ok(/\\u001[fF]/.test(tricky), 'JSON 은 제어문자를 이스케이프해야 한다');
  assert.ok(!tricky.includes(SEP), 'raw U+001F 가 본문에 남아 있으면 안 된다');

  const { sandbox, captured } = newBrowser({ routes: { [FYP]: tricky } });
  await sandbox.fetch(FYP);
  await settle();
  assert.equal(parse(captured[0]).body, tricky);
  assert.equal(JSON.parse(parse(captured[0]).body).desc, desc);
});

test('fetch 실패가 예외로 새어나가지 않는다', async () => {
  const { sandbox, captured } = newBrowser({ routes: {} });
  await assert.rejects(() => sandbox.fetch(FYP), /404/);
  await settle();
  assert.equal(captured.length, 0);
});

test('responseText 가 throw 하는 XHR 도 앱을 죽이지 않는다', async () => {
  const { sandbox, captured } = newBrowser({ routes: { [DETAIL]: DETAIL_BODY } });
  const xhr = new sandbox.XMLHttpRequest();
  xhr.responseType = 'blob';
  xhr.open('GET', DETAIL);
  xhr.send();                                 // throw 하면 여기서 터진다
  await settle();
  assert.equal(captured.length, 0);
});

test('Request 객체로 호출해도 URL 을 인식한다', async () => {
  const { sandbox, captured } = newBrowser({ routes: { [FYP]: FYP_BODY } });
  await sandbox.fetch({ url: FYP, method: 'GET' });
  await settle();
  assert.equal(captured.length, 1);
  assert.equal(parse(captured[0]).url, FYP);
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log('  PASS  ' + name);
  } catch (err) {
    failed++;
    console.log('  FAIL  ' + name + '\n        ' + (err.message || err));
  }
}
console.log(`\n${tests.length - failed}/${tests.length} 통과`);
process.exit(failed ? 1 : 0);
