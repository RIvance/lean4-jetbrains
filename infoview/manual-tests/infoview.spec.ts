import { expect, test } from '@playwright/test';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createMessageConnection, StreamMessageReader, StreamMessageWriter } from 'vscode-jsonrpc/node';

/** External harness: drives the public EditorApi and a real Lean server, without substituting RPC results. */
test('renders interactive goals, pinned states, and project widgets through the host bridge', async ({ page }) => {
    const executable = process.env.LEAN_TEST_EXECUTABLE;
    if (!executable) throw new Error('Set LEAN_TEST_EXECUTABLE for this manual browser check.');
    const directory = await mkdtemp(path.join(tmpdir(), 'lean-infoview-browser-'));
    const source = [
        'import Lean',
        '',
        '@[widget_module] def counterWidget : Lean.Widget.Module where',
        '  javascript := "import * as React from \'react\'; export default function() { ' +
            'const [n, setN] = React.useState(0); return React.createElement(\'button\', ' +
            '{onClick: () => setN(n + 1)}, \'Widget count: \' + n); }"',
        '',
        '#widget counterWidget',
        '',
        'example (n : Nat) : n = n := by',
        '  rfl',
        ''
    ].join('\n');
    const filename = path.join(directory, 'Main.lean');
    await writeFile(filename, source);
    const uri = pathToFileURL(filename).href;
    const server = spawn(executable, ['--server', directory], { cwd: directory });
    const connection = createMessageConnection(
        new StreamMessageReader(server.stdout), new StreamMessageWriter(server.stdin)
    );
    const notifications: object[] = [];
    const errors: string[] = [];
    const stderr: string[] = [];
    server.stderr.on('data', data => stderr.push(data.toString()));
    server.on('error', error => errors.push(error.message));
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    let connected = false;
    const sendEvent = async (event: object) => page.evaluate(event => window.leanReceive?.({ event }), event);
    connection.onNotification((method, params) => {
        const event = { type: 'notification', serverId: 'test-server', direction: 'Server', method, params };
        notifications.push(event);
        if (connected) void sendEvent(event);
    });
    connection.onRequest(() => null);
    connection.listen();
    const initialize = await connection.sendRequest('initialize', {
        processId: process.pid,
        rootUri: pathToFileURL(directory).href,
        capabilities: { lean: { rpcWireFormat: 'v1' } },
        initializationOptions: { hasWidgets: true }
    });
    await connection.sendNotification('initialized', {});
    await connection.sendNotification('textDocument/didOpen', {
        textDocument: { uri, languageId: 'lean4', version: 1, text: source }
    });
    const staticServer = createServer(async (request, response) => {
        const url = new URL(request.url!, 'http://localhost');
        const name = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
        try {
            const bytes = await readFile(path.join(import.meta.dirname, '../dist', name));
            const types: Record<string, string> = {
                '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css'
            };
            response.setHeader('Content-Type', types[path.extname(name)] ?? 'application/octet-stream');
            response.end(bytes);
        } catch {
            response.writeHead(404).end();
        }
    });
    await new Promise<void>(resolve => staticServer.listen(0, '127.0.0.1', resolve));
    const address = staticServer.address() as { port: number };
    const cursor = (line: number, character: number) => ({
        type: 'cursor', serverId: 'test-server', documentStatus: 'Synchronized', location: {
            uri, range: { start: { line, character }, end: { line, character } }
        }
    });
    const requests: string[] = [];
    let clipboard = '';
    let controlsAvailable = false;
    const openGoalMenu = async () => {
        await page.locator('[data-vscode-context*="copyStateId"]').first().evaluate(element => {
            element.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
        });
        await expect(page.getByRole('menu')).toBeVisible();
    };
    await page.exposeFunction('nativeCommand', async (raw: string) => {
        const { id, command } = JSON.parse(raw);
        try {
            let result: unknown = null;
            switch (command.type) {
                case 'ready':
                    await sendEvent({ type: 'server', server: {
                        id: 'test-server', root: pathToFileURL(directory).href
                    } });
                    await sendEvent({ type: 'cursor', serverId: 'test-server' });
                    break;
                case 'connect':
                    result = (await connection.sendRequest<{ sessionId: string }>(
                        '$/lean/rpc/connect', { uri: command.uri }
                    )).sessionId;
                    break;
                case 'request':
                    requests.push(command.params.method ?? command.method);
                    result = await connection.sendRequest(command.method, command.params);
                    break;
                case 'notify':
                    await connection.sendNotification(command.method, command.params);
                    break;
                case 'disconnect': break; // Lean sessions expire once their host stops sending keep-alives.
                case 'clipboard': clipboard = command.text; break;
                case 'controls': controlsAvailable = command.enabled; break;
                default: throw new Error(`Unexpected editor operation: ${command.type}`);
            }
            await page.evaluate(reply => window.leanReceive?.({ reply }), { id, type: 'success', result });
        } catch (error: any) {
            await page.evaluate(reply => window.leanReceive?.({ reply }), {
                id, type: 'error', code: error.code ?? -32603, message: error.message
            });
        }
    });
    await page.addInitScript(() => {
        window.leanHost = raw => { void (window as any).nativeCommand(raw); };
    });
    try {
        await page.goto(`http://127.0.0.1:${address.port}/`);
        await expect(page.locator('#status')).toHaveText('Starting Lean server…');
        expect(controlsAvailable).toBe(false);
        await expect(page.locator('[data-action="togglePin"]')).toBeDisabled();
        await sendEvent({ type: 'server', server: {
            id: 'test-server', root: pathToFileURL(directory).href, stopped: 'The selected toolchain is unavailable'
        } });
        await expect(page.locator('#status')).toHaveText('The selected toolchain is unavailable');
        connected = true;
        await sendEvent({ type: 'server', server: {
            id: 'test-server', root: pathToFileURL(directory).href, result: initialize
        } });
        for (const event of notifications) await sendEvent(event);
        await sendEvent(cursor(8, 2));
        await expect(page.locator('#workspaces')).toContainText('n : Nat');
        await expect(page.locator('#workspaces')).toContainText('⊢ n = n');
        await expect.poll(() => controlsAvailable).toBe(true);
        expect(requests).toContain('Lean.Widget.getInteractiveGoals');
        await openGoalMenu();
        await page.getByRole('menuitem', { name: 'Copy state', exact: true }).evaluate(button => {
            (button as HTMLButtonElement).click();
        });
        await expect.poll(() => clipboard).toContain('n : Nat');
        await page.evaluate(() => window.leanReceive?.({ action: { kind: 'togglePin' } }));
        await sendEvent(cursor(5, 5));
        await expect(page.getByRole('button', { name: 'Widget count: 0', exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Widget count: 0', exact: true }).evaluate(button => {
            (button as HTMLButtonElement).click();
        });
        await expect(page.getByRole('button', { name: 'Widget count: 1', exact: true })).toBeVisible();
        await expect(page.locator('#workspaces')).toContainText('⊢ n = n');
        expect(requests).toContain('Lean.Widget.getWidgetSource');

        await openGoalMenu();
        await sendEvent({ type: 'cursor' });
        await expect(page.getByRole('menu')).toHaveCount(0);
        await expect(page.locator('#status')).toContainText('Open a Lean file');
        await sendEvent(cursor(8, 2));
        await expect.poll(() => controlsAvailable).toBe(true);
        await openGoalMenu();
        await sendEvent({ type: 'server', server: {
            id: 'test-server', root: pathToFileURL(directory).href, result: initialize, stopped: 'Server stopped'
        } });
        await expect(page.getByRole('menu')).toHaveCount(0);
        await expect.poll(() => controlsAvailable).toBe(false);
        await sendEvent({ type: 'server', server: {
            id: 'test-server', root: pathToFileURL(directory).href, result: initialize
        } });
        await expect.poll(() => controlsAvailable).toBe(true);
        await openGoalMenu();
        await page.evaluate(() => window.leanReceive?.({ closed: 'Connection ended' }));
        await expect(page.getByRole('menu')).toHaveCount(0);
        await expect(page.locator('#workspaces')).toHaveAttribute('inert', '');
        await expect(page.locator('[data-action="togglePin"]')).toBeDisabled();
        expect(errors).toEqual([]);
        expect(stderr.join('')).not.toContain('error:');
    } finally {
        connected = false;
        await connection.sendRequest('shutdown', null);
        await connection.sendNotification('exit');
        connection.dispose();
        server.kill();
        staticServer.close();
        await rm(directory, { recursive: true, force: true });
    }
});
