import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../.opencode/plugins/better-design.js', import.meta.url), 'utf8');
let fixtureID = 0;
async function fixture(client = {}) {
  const { default: plugin } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${fixtureID++}`);
  assert.equal(plugin.id, 'better-design');
  assert.equal(typeof plugin.server, 'function');
  const hooks = await plugin.server({ directory: new URL('../../', import.meta.url).pathname, client });
  return hooks;
}
let tick = 0;
const call = (id, name, input) => ({ type: 'tool', id: `prt_${id}`, sessionID: 'test', messageID: 'msg_test', callID: id, tool: name,
  state: { status: 'running', input, time: { start: ++tick } } });
const result = (id, value, extra = {}) => ({ resultFor: id, value, extra, end: ++tick });
const edit = (path = 'apps/android/app/src/main/Main.kt') => call(`edit-${tick}`, 'edit', { filePath: path });
const checks = (command = './gradlew :app:compileDebugKotlin :core:data:testDebugUnitTest', workdir = '/repo/apps/android') => call('check', 'bash', { command, workdir });
// Build native persisted ToolParts, where each completed part owns its result.
// Adapter-like values from the older regression cases become native output and
// metadata, rather than inventing tool-call/tool-result messages for the hook.
function nativeParts(parts) {
  const native = [];
  let time = 0;
  for (const item of parts) {
    if (!item.resultFor) {
      const part = structuredClone(item);
      if (part.state.status === 'running') part.state.time = { start: ++time };
      native.push(part);
      continue;
    }
    const tool = native.findLast((part) => part.callID === item.resultFor);
    const value = item.value;
    const metadata = { ...(typeof value === 'object' && !Array.isArray(value) ? value : {}), ...item.extra, ...item.extra.metadata };
    const output = typeof value === 'string' ? value : Array.isArray(value)
      ? value.map((part) => part.text ?? '').join('\n') : value?.stdout ?? value?.output ?? '';
    tool.state = { ...tool.state, status: metadata.type === 'error' || metadata.error ? 'error' : 'completed',
      output, title: 'check', metadata, time: { ...tool.state.time, end: ++time },
      ...(metadata.error ? { error: String(metadata.error) } : {}) };
  }
  return native;
}
async function nudge(parts, providerID = 'ollama', hooks) {
  hooks ??= await fixture();
  const messages = [{ info: { id: 'msg_test', sessionID: 'test', role: 'assistant', providerID }, parts: nativeParts(parts) }];
  await hooks['experimental.chat.messages.transform']({}, { messages });
  const output = { system: [] };
  await hooks['experimental.chat.system.transform']({ sessionID: 'test', model: { providerID } }, output);
  return output.system[0] ?? '';
}
const reviewCalls = Array.from({ length: 3 }, (_, index) => call(`review-${index}`, 'read', {}));

test('exports the installed V1 module contract and native hooks without replacing tools', async () => {
  const hooks = await fixture();
  assert.deepEqual(Object.keys(hooks).sort(), ['chat.message', 'dispose', 'experimental.chat.messages.transform', 'experimental.chat.system.transform']);
});
test('native chat.message attaches checked-in skill once and preserves prompt parts', async () => {
  const hooks = await fixture();
  const text = { id: 'prt_user', type: 'text', text: 'Build a dashboard', sessionID: 'test', messageID: 'msg_user' };
  const output = { message: { id: 'msg_user', role: 'user', sessionID: 'test' }, parts: [text] };
  await hooks['chat.message']({ sessionID: 'test' }, output);
  await hooks['chat.message']({ sessionID: 'test' }, output);
  assert.equal(output.parts.length, 2);
  assert.deepEqual(output.parts[0], text);
  assert.equal(output.parts[1].synthetic, true);
  assert.equal(output.parts[1].messageID, 'msg_user');
  assert.equal(output.parts[1].sessionID, 'test');
  assert.match(output.parts[1].text, /# Better Design/);
});
test('non-interface prompts do not attach a skill or contact MCP', async () => {
  const hooks = await fixture({ mcp: { status: () => { throw new Error('should not call'); } } });
  const output = { message: { id: 'msg_user' }, parts: [{ type: 'text', text: 'Explain this database query' }] };
  await hooks['chat.message']({ sessionID: 'test' }, output);
  assert.equal(output.parts.length, 1);
});
test('uses native client.mcp.status keyed response and waits through pending', async () => {
  let calls = 0;
  const hooks = await fixture({ mcp: { status: async () => ({ data: { 'better-design': { status: ++calls === 1 ? 'pending' : 'connected' } } }) } });
  const output = { message: { id: 'msg_user' }, parts: [{ type: 'text', text: 'Build a dashboard' }] };
  await hooks['chat.message']({ sessionID: 'test' }, output);
  assert.equal(calls, 2);
});
test('MCP failure does not block submitting a prompt', async () => {
  const hooks = await fixture({ mcp: { status: async () => { throw new Error('offline'); } } });
  await hooks['chat.message']({ sessionID: 'test' }, { message: { id: 'msg_user' }, parts: [{ type: 'text', text: 'Build UI' }] });
});
test('unresponsive native status is aborted and prompt resumes at the 15 second deadline', async (t) => {
  let notifyStarted;
  const started = new Promise((resolve) => { notifyStarted = resolve; });
  let signal;
  const hooks = await fixture({ mcp: { status: (options) => {
    signal = options.signal;
    notifyStarted();
    // Deliberately ignore abort, proving the race also bounds a stuck adapter.
    return new Promise(() => {});
  } } });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 });
  t.after(() => t.mock.timers.reset());
  let settled = false;
  const submission = hooks['chat.message']({ sessionID: 'test' }, {
    message: { id: 'msg_user' }, parts: [{ type: 'text', text: 'Build UI' }],
  }).then(() => { settled = true; });
  await started;
  t.mock.timers.tick(14999);
  await Promise.resolve();
  assert.equal(settled, false);
  assert.equal(signal.aborted, false);
  t.mock.timers.tick(1);
  await submission;
  assert.equal(settled, true);
  assert.equal(signal.aborted, true);
});
test('successful native status clears its deadline timer', async (t) => {
  let signal;
  const hooks = await fixture({ mcp: { status: async (options) => {
    signal = options.signal;
    return { data: { 'better-design': { status: 'connected' } } };
  } } });
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 });
  t.after(() => t.mock.timers.reset());
  await hooks['chat.message']({ sessionID: 'test' }, {
    message: { id: 'msg_user' }, parts: [{ type: 'text', text: 'Build UI' }],
  });
  t.mock.timers.tick(15000);
  assert.equal(signal.aborted, false);
});
test('successful Android compile and unit tests pass without requesting npm', async () => {
  const parts = [edit(), checks(), result('check', 'BUILD SUCCESSFUL in 4s'), ...reviewCalls];
  const text = await nudge(parts);
  assert.match(text, /gradlew.*passed/);
  assert.match(text, /working directory: \/repo\/apps\/android/);
  assert.doesNotMatch(text, /npm/);
});
test('failed Android build requests the actual Gradle verification', async () => {
  const text = await nudge([edit(), checks(), result('check', 'FAILURE: Build failed with an exception.\nBUILD FAILED')]);
  assert.match(text, /gradlew.*failed/);
  assert.doesNotMatch(text, /npm/);
});
for (const prefix of [
  "JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ",
  'env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" GRADLE_OPTS=-Xmx2g ',
]) {
  test(`Android verification accepts leading environment assignments: ${prefix}`, async () => {
    const command = `${prefix}./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`;
    const text = await nudge([edit(), checks(command), result('check', 'BUILD SUCCESSFUL'), ...reviewCalls]);
    assert.match(text, /passed and no files/);
    assert.ok(text.includes(command));
    assert.doesNotMatch(text, /npm/);
  });
}
test('editing after a successful Android build invalidates verification', async () => {
  const text = await nudge([checks(), result('check', 'BUILD SUCCESSFUL'), edit()]);
  assert.match(text, /have not verified those edits/);
  assert.match(text, /Gradle compile\/test checks in apps\/android/);
});
for (const command of ['npm run build', 'npx vitest run', 'npx tsc --noEmit']) {
  test(`web check uses apps/web cwd for ${command}`, async () => {
    const text = await nudge([edit('apps/web/src/app/page.tsx'), checks(command, '/repo/apps/web'), result('check', { output: '', exitCode: 0 }), ...reviewCalls]);
    assert.match(text, /passed/);
    assert.ok(text.includes(command));
    assert.match(text, /working directory: \/repo\/apps\/web/);
  });
}
test('web check resolves an inline apps/web directory', async () => {
  const text = await nudge([checks('cd apps/web && npm run build', '/repo'), result('check', { stdout: 'compiled successfully', exit_code: 0 }), ...reviewCalls]);
  assert.match(text, /working directory: \/repo\/apps\/web/);
});
test('a fresh web edit points to documented web checks', async () => {
  assert.match(await nudge([edit('apps/web/src/app/page.tsx')]), /documented web build\/test checks in apps\/web/);
});
for (const failure of [
  result('check', { stdout: 'BUILD SUCCESSFUL', exitCode: 1 }),
  result('check', 'BUILD SUCCESSFUL', { metadata: { exit: 1 } }),
  result('check', { output: 'built in 2s', exit_code: 2 }),
  result('check', 'BUILD SUCCESSFUL', { isError: true }),
  result('check', '', { type: 'error', error: 'command failed' }),
  result('check', [{ type: 'text', text: 'Error: compilation failed' }]),
  result('check', '', { error: 'process failed' }),
  result('check', 'BUILD SUCCESSFUL\nBUILD FAILED', { exitCode: 0 }),
]) {
  test(`failure never passes: ${JSON.stringify(failure)}`, async () => {
    const text = await nudge([checks(), failure, ...reviewCalls]);
    assert.match(text, /failed/);
    assert.doesNotMatch(text, /passed and no files/);
  });
}
for (const value of ['', [], { stdout: 'daemon started' }]) {
  test(`unknown result is unverified: ${JSON.stringify(value)}`, async () => {
    assert.match(await nudge([checks(), result('check', value), ...reviewCalls]), /no confirmed successful completion/);
  });
}
test('a failed later check supersedes an earlier pass', async () => {
  const parts = [checks(), result('check', 'BUILD SUCCESSFUL'), call('later', 'bash', { command: './gradlew :app:testDebugUnitTest' }), result('later', { output: '', exitCode: 1 })];
  assert.match(await nudge(parts), /failed/);
});
test('a build result cannot validate an edit made while it ran', async () => {
  assert.match(await nudge([checks(), edit(), result('check', 'BUILD SUCCESSFUL')]), /have not verified/);
});
test('a tool mention in echo is not verification', async () => {
  assert.match(await nudge([edit(), checks('echo "npm run build"'), result('check', { exitCode: 0 })]), /have not verified/);
});
test('structured web output and zero failed tests are accepted', async () => {
  const parts = [checks('npx vitest run', '/repo/apps/web'), result('check', { stdout: 'Tests 2 passed, 0 failed', exitCode: 0 }), ...reviewCalls];
  assert.match(await nudge(parts), /passed and no files/);
});
test('local finish nudges remain capped and do not affect hosted providers', async () => {
  const context = await fixture();
  const parts = [edit()];
  for (let count = 0; count < 6; count++) assert.ok(await nudge(parts, 'ollama', context));
  assert.equal(await nudge(parts, 'ollama', context), '');
  assert.equal(await nudge(parts, 'openai'), '');
});

test('native running build output is never success proof', async () => {
  const part = checks();
  part.state.metadata = { output: 'BUILD SUCCESSFUL', exit: 0 };
  assert.match(await nudge([part]), /no confirmed successful completion/);
});
test('native completed result honors metadata exit over successful-looking output', async () => {
  const part = checks();
  part.state = { status: 'completed', input: part.state.input, title: 'gradle', output: 'BUILD SUCCESSFUL', metadata: { exit: 1 }, time: { start: ++tick, end: ++tick } };
  assert.match(await nudge([part]), /failed/);
});
test('native tool error remains failed even if its text looks successful', async () => {
  const part = checks();
  part.state = { status: 'error', input: part.state.input, error: 'BUILD SUCCESSFUL', time: { start: ++tick, end: ++tick } };
  assert.match(await nudge([part]), /failed/);
});
test('native assistant history is isolated by session and actual dispatched provider', async () => {
  const hooks = await fixture();
  await nudge([edit()], 'openai', hooks);
  const output = { system: [] };
  await hooks['experimental.chat.system.transform']({ sessionID: 'other', model: { providerID: 'ollama' } }, output);
  assert.deepEqual(output.system, []);
  await hooks.dispose();
  await hooks['experimental.chat.system.transform']({ sessionID: 'test', model: { providerID: 'ollama' } }, output);
  assert.deepEqual(output.system, []);
});
test('native completion cannot validate edits in later messages while the build ran', async () => {
  const hooks = await fixture();
  const build = checks();
  build.state = { status: 'completed', input: build.state.input, title: 'gradle', output: 'BUILD SUCCESSFUL', metadata: { exit: 0 }, time: { start: 10, end: 30 } };
  const changed = edit();
  changed.state.time = { start: 20 };
  await hooks['experimental.chat.messages.transform']({}, { messages: [
    { info: { sessionID: 'test', role: 'assistant' }, parts: [build] },
    { info: { sessionID: 'test', role: 'assistant' }, parts: [changed] },
  ] });
  const output = { system: [] };
  await hooks['experimental.chat.system.transform']({ sessionID: 'test', model: { providerID: 'ollama' } }, output);
  assert.match(output.system[0], /have not verified those edits/);
});
