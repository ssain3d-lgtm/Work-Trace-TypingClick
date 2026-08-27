/**
 * 앱에서 내보낸 JSONL 을 검증한다.
 *
 * Phase 1 의 인수 기준은 "쓸 수 있는 원본이 실제로 쌓였는가" 하나다.
 * 기기에서 [진단 > JSONL 내보내기] 로 받은 파일을 이걸로 통과시켜 확인한다.
 *
 *   node android/tools/validate-export.mjs ~/Downloads/tiktrace-20260827-1432.jsonl
 */
import { readFileSync } from 'node:fs';

const path = process.argv[2];
if (!path) {
  console.error('사용법: node validate-export.mjs <export.jsonl>');
  process.exit(2);
}

const lines = readFileSync(path, 'utf8').split('\n').filter((l) => l.length > 0);

const problems = [];
const surfaces = new Map();
const videoIds = new Set();
const creators = new Set();
let itemsDeclared = 0;
let itemsFound = 0;
let earliest = Infinity;
let latest = -Infinity;

/** 응답 형태별로 아이템 배열을 꺼낸다. hook 이 잡는 4가지 엔드포인트를 모두 다룬다. */
function extractItems(body) {
  if (!body || typeof body !== 'object') return [];
  if (Array.isArray(body.itemList)) return body.itemList;
  if (body.itemInfo?.itemStruct) return [body.itemInfo.itemStruct];
  if (Array.isArray(body.data)) return body.data.map((d) => d?.item).filter(Boolean);
  return [];
}

lines.forEach((line, i) => {
  const at = `${i + 1}번째 줄`;
  let row;
  try {
    row = JSON.parse(line);
  } catch (e) {
    problems.push(`${at}: JSON 파싱 실패 — ${e.message}`);
    return;
  }

  for (const key of ['captured_at', 'url', 'surface', 'item_count', 'body']) {
    if (!(key in row)) problems.push(`${at}: '${key}' 필드가 없다`);
  }
  if (typeof row.captured_at !== 'number') problems.push(`${at}: captured_at 이 숫자가 아니다`);

  earliest = Math.min(earliest, row.captured_at);
  latest = Math.max(latest, row.captured_at);
  surfaces.set(row.surface, (surfaces.get(row.surface) ?? 0) + 1);
  itemsDeclared += row.item_count ?? 0;

  if (typeof row.body === 'string') {
    problems.push(`${at}: body 가 JSON 이 아니라 문자열로 감싸졌다 (오류 응답을 캡처했을 수 있다)`);
    return;
  }

  const items = extractItems(row.body);
  itemsFound += items.length;
  if (items.length !== row.item_count) {
    problems.push(`${at}: item_count=${row.item_count} 인데 본문에는 ${items.length}개`);
  }

  for (const item of items) {
    if (item.id) videoIds.add(item.id);
    if (item.author?.uniqueId) creators.add(item.author.uniqueId);
    // Tier 1 은 (likes, age) 두 값만 있으면 성립한다. 그게 실제로 들어왔는지 본다.
    const likes = Number(item.statsV2?.diggCount ?? item.stats?.diggCount ?? NaN);
    if (!Number.isFinite(likes)) problems.push(`${at}: 영상 ${item.id} 에 좋아요 수가 없다`);
    if (!item.createTime) problems.push(`${at}: 영상 ${item.id} 에 createTime 이 없다`);
  }
});

const fmt = (ms) => (Number.isFinite(ms) ? new Date(ms).toISOString().replace('T', ' ').slice(0, 16) : '-');

console.log(`파일          ${path}`);
console.log(`응답          ${lines.length}건`);
console.log(`기간          ${fmt(earliest)} ~ ${fmt(latest)} (UTC)`);
console.log(`화면별        ${[...surfaces].map(([k, v]) => `${k}=${v}`).join(' · ') || '-'}`);
console.log(`영상          선언 ${itemsDeclared}개 / 실제 ${itemsFound}개 / 고유 ${videoIds.size}개`);
console.log(`크리에이터    ${creators.size}명`);

if (problems.length) {
  console.log(`\n문제 ${problems.length}건:`);
  for (const p of problems.slice(0, 20)) console.log('  - ' + p);
  if (problems.length > 20) console.log(`  ... 외 ${problems.length - 20}건`);
  process.exit(1);
}
console.log('\n이상 없음 — Tier 1 계산에 필요한 (좋아요, createTime) 이 모두 들어 있다.');
