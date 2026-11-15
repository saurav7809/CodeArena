import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';

const origin = new URL(process.argv[2] ?? 'http://127.0.0.1:8088');
const username = 'ci_' + randomUUID().slice(0, 8);
const password = 'Ci!' + randomUUID();
let token;

async function request(path, data, headers = {}) {
  const response = await fetch(new URL(path, origin), {
    method: data ? 'POST' : 'GET',
    headers: {
      ...(token && { Authorization: 'Bearer ' + token }),
      ...(data && { 'Content-Type': 'application/json', Origin: origin.origin }),
      ...headers,
    },
    body: data ? JSON.stringify(data) : undefined,
    signal: AbortSignal.timeout(10000),
  });
  assert.ok(response.ok, path + ' returned HTTP ' + response.status);
  return response.json();
}

const career = await fetch(new URL('/career/backend', origin), {
  headers: { Accept: 'text/html' }, signal: AbortSignal.timeout(10000),
});
assert.equal(career.status, 200);
assert.match(await career.text(), /<title>CodeArena/);

const health = await request('/api/health');
assert.equal(health.status, 'UP');
assert.equal(health.judgeConfigured, true);
const { problem, examples } = await request('/api/problems/slug/deduplicate-catalog');
assert.equal(examples.length, 2);
({ token } = await request('/api/auth/register', {
  fullName: 'Release verification', username, email: username + '@example.test', password, confirmPassword: password,
}));

const key = randomUUID();
const code = "import sys\n_, *items = sys.stdin.read().split()\nprint(' '.join(dict.fromkeys(items)))";
const submission = { problemId: problem.id, language: 'PYTHON', code };
const [first, replay] = await Promise.all([1, 2].map(() =>
  request('/api/submissions/submit', submission, { 'Idempotency-Key': key })));
assert.equal(first.id, replay.id);

for (let attempt = 0; attempt < 60; attempt++) {
  const result = await request('/api/submissions/' + first.id + '/result');
  if (['PENDING', 'RUNNING'].includes(result.submission.verdict)) {
    await pause(500);
    continue;
  }
  assert.equal(result.submission.verdict, 'ACCEPTED');
  assert.equal(result.submission.passedCases, 4);
  assert.equal(result.cases.length, 2);
  assert.equal(result.cases[0].expectedOutput, examples[0].expectedOutput);
  assert.equal(result.code, undefined);
  assert.ok(!JSON.stringify(result).includes('Desk desk DESK'));
  console.log('CodeArena smoke check passed. One submission was created, judged, and returned with only public cases.');
  process.exit(0);
}
throw new Error('The submission did not reach a final verdict.');
