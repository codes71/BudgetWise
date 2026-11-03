import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../.opencode/plugins/better-design.js', import.meta.url), 'utf8');
let fixtureID = 0;
async function fixture() {
  const { default: plugin } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${fixtureID++}`);
  const hooks = {};
  await plugin.setup({ session: { hook: async (name, hook) => { hooks[name] = hook; } } });
  return hooks;
}
const call = (id, name, input) => ({ type: 'tool-call', id, name, input });
const result = (id, value, extra = {}) => ({ type: 'tool-result', id, result: { value, ...extra } });
const edit = (path = 'apps/android/app/src/main/Main.kt') => call('edit', 'edit', { filePath: path });
const checks = (command = './gradlew :app:compileDebugKotlin :core:data:testDebugUnitTest', workdir = '/repo/apps/android') => call('check', 'bash', { command, workdir });
async function nudge(parts, providerID = 'ollama', hook) {
  const context = hook ?? (await fixture()).context;
  const turn = { sessionID: 'test', model: { providerID }, messages: [{ role: 'assistant', content: parts }] };
  await context(turn);
  return turn.messages[1]?.content[0]?.text ?? '';
}
const reviewCalls = Array.from({ length: 3 }, (_, index) => call(`review-${index}`, 'read', {}));

for (const providerID of ['ollama', 'lmstudio', 'llamacpp', 'llama.cpp', 'openai', 'anthropic']) {
  test(`preserves every workflow tool for ${providerID}`, async () => {
    const { context } = await fixture();
    const names = ['find-design-system', 'get-design-system-kit', 'get-ui-principle', 'get-ux-principle', 'get-review-rules', 'check-comprehension', 'inspect-spacing', 'get-react-native-guide', 'get-widget-guide', 'get-three-js-guide', 'get-shopify-guide', 'get-sales-principle', 'extract-from-url', 'create-design-system', 'get-design-system-status', 'finalize-dashboard-review', 'future-required-tool'];
    const tools = Object.fromEntries(names.map((name) => [`better-design_${name}`, { name }]));
    tools.bash = { name: 'bash' };
    const expected = structuredClone(tools);
    await context({ sessionID: 'test', model: { providerID }, messages: [], tools });
    assert.deepEqual(tools, expected);
  });
}
test('prompt hook still attaches the design skill once', async () => {
  const { prompt } = await fixture();
  const event = { prompt: { text: 'Build a dashboard' } };
  await prompt(event);
  await prompt(event);
  assert.equal(event.prompt.skills.length, 1);
  assert.equal(event.prompt.skills[0].id, 'better-design');
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
  { type: 'tool-result', id: 'check', result: { error: { message: 'process failed' } } },
  result('check', 'BUILD SUCCESSFUL\nBUILD FAILED', { exitCode: 0 }),
]) {
  test(`failure never passes: ${JSON.stringify(failure.result)}`, async () => {
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
  const { context } = await fixture();
  const parts = [edit()];
  for (let count = 0; count < 6; count++) assert.ok(await nudge(parts, 'ollama', context));
  assert.equal(await nudge(parts, 'ollama', context), '');
  assert.equal(await nudge(parts, 'openai'), '');
});
