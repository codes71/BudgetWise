import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const clients = [
  ['.mcp.json', 'mcpServers', 'http', 'claude-code', 'Bearer ${BETTER_DESIGN_API_KEY}'],
  ['.vscode/mcp.json', 'servers', 'http', 'vscode', 'Bearer ${env:BETTER_DESIGN_API_KEY}'],
  ['opencode.json', 'mcp', 'remote', 'opencode', 'Bearer {env:BETTER_DESIGN_API_KEY}'],
];

for (const [path, group, type, client, authorization] of clients) {
  test(`${client} uses its supported credential reference`, async () => {
    const data = JSON.parse(await readFile(new URL(path, root), 'utf8'));
    const server = data[group]['better-design'];
    assert.equal(server.type, type);
    assert.equal(server.url, 'https://better-design.com/api/mcp');
    assert.equal(server.headers.Authorization, authorization);
    assert.equal(server.headers['X-Better-Design-Client'], client);
  });
}
