import '@leanprover/infoview/loader';
import type {
    EditorApi, InfoviewApi, InfoviewAction, InfoviewActionKind, InfoviewConfig,
    LeanFileProgressParams, ServerStoppedReason
} from '@leanprover/infoview-api';
import type { InitializeResult, Location } from 'vscode-languageserver-protocol';
import { HostBridge } from './bridge.js';
import { InfoviewContextMenu } from './contextMenu.js';

interface Server { id: string; root: string; result?: InitializeResult; stopped?: string }
type DocumentStatus = 'Waiting' | 'Synchronized' | 'Closed';
const closedDocument: ServerStoppedReason = {
    message: 'Lean is no longer tracking this file.',
    reason: 'Use Restart Lean Servers to reconnect.'
};
declare const importShim: {
    (name: string): Promise<typeof import('@leanprover/infoview')>;
    addImportMap(map: { imports: Record<string, string> }): void;
};
interface Notification {
    type: 'notification'; serverId: string; direction: 'Server' | 'Client'; method: string; params: any;
}
interface DocumentState {
    readonly version?: number;
    readonly notifications: Map<string, Notification>;
    stopped?: ServerStoppedReason;
}
type Event = { type: 'server'; server: Server } | Notification |
    { type: 'cursor'; serverId?: string; location?: Location; documentStatus?: DocumentStatus } | { type: 'ready' };

const imports = Object.fromEntries([
    ['@leanprover/infoview', 'index'], ['react', 'react'],
    ['react/jsx-runtime', 'react-jsx-runtime'], ['react-dom', 'react-dom']
].map(([name, file]) => [name, new URL(`vendor/${file}.production.min.js`, location.href).href]));

const roots = new Map<string, Workspace>();
let active: Workspace | undefined;
let config: InfoviewConfig | undefined;
let delivery = Promise.resolve();
let reportedActionAvailability: boolean | undefined;
const actionButtons = document.querySelectorAll<HTMLButtonElement>('[data-action]');
const bridge = new HostBridge(
    event => enqueue(() => receive(event as Event)),
    action => enqueue(() => requestAction(action)),
    error => {
        for (const workspace of roots.values()) workspace.contextMenu.dispose();
        document.getElementById('workspaces')!.inert = true;
        updateActionAvailability();
        showError(error);
    }
);
importShim.addImportMap({ imports });
const renderer = importShim('@leanprover/infoview');
void renderer.catch(showError);

function enqueue(operation: () => Promise<void>): void {
    delivery = delivery.then(async () => {
        if (!bridge.isClosed) await operation();
    }).then(updateActionAvailability).catch(showError);
}

function updateActionAvailability(): void {
    const available = !bridge.isClosed && (active?.canRequestAction ?? false);
    for (const workspace of roots.values()) {
        if (workspace !== active || !available) workspace.contextMenu.dismiss();
    }
    for (const button of actionButtons) button.disabled = !available;
    if (reportedActionAvailability === available) return;
    reportedActionAvailability = available;
    if (bridge.isClosed) return;
    void bridge.call({ type: 'controls', enabled: available }).catch(showError);
}

async function requestAction(action: InfoviewAction): Promise<void> {
    const workspace = active;
    if (!bridge.isClosed && workspace?.canRequestAction) await (await workspace.api).requestedAction(action);
}

function showError(error: unknown): void {
    const status = document.getElementById('status')!;
    status.hidden = false;
    status.classList.add('host-error');
    const message = error instanceof Error ? error.message : (error as { message?: string })?.message;
    status.textContent = String(message ?? error);
}

/** Each workspace keeps its own upstream React tree, pins, capabilities, and subscription counts. */
class Workspace {
    readonly element = document.createElement('section');
    readonly api: Promise<InfoviewApi>;
    readonly contextMenu: InfoviewContextMenu;
    private initialized = false;
    private documentStatus: DocumentStatus = 'Waiting';
    private initializedServer?: string;
    private activeUri?: string;
    private readonly subscriptions = { Server: new Map<string, number>(), Client: new Map<string, number>() };
    // Edits and reopenings replace the state, so late failures cannot mark a new revision as stopped.
    private readonly documents = new Map<string, DocumentState>();

    get canRequestAction(): boolean {
        return (
            this.initialized && this.documentStatus === 'Synchronized' && this.initializedServer === this.server.id &&
            !this.server.stopped && this.activeUri !== undefined && !this.documents.get(this.activeUri)?.stopped
        );
    }

    constructor(public server: Server) {
        this.element.className = 'workspace';
        this.element.hidden = true;
        document.getElementById('workspaces')!.append(this.element);
        this.api = renderer.then(module => module.renderInfoview(this.editorApi(), this.element));
        this.contextMenu = new InfoviewContextMenu(
            this.element, this.api,
            () => active === this && !bridge.isClosed && this.canRequestAction,
            showError
        );
    }

    async update(server: Server): Promise<void> {
        if (this.server.id !== server.id || server.stopped) this.contextMenu.dismiss();
        if (this.server.id !== server.id) {
            this.documents.clear();
            this.documentStatus = 'Waiting';
            this.activeUri = undefined;
        }
        this.server = server;
        await this.updateStatus();
        const api = await this.api;
        if (config) await api.changedInfoviewConfig(config);
        if (server.result && this.initializedServer !== server.id) {
            this.initializedServer = server.id;
            await api.serverRestarted(server.result);
        }
        if (server.stopped) this.documents.clear();
        await this.updateStopped();
    }

    async cursor(location: Location | null | undefined, status: DocumentStatus = 'Waiting'): Promise<void> {
        if (active !== this) {
            if (active) {
                active.contextMenu.dismiss();
                active.element.hidden = true;
            }
            active = this;
            this.element.hidden = false;
        }
        if (this.activeUri !== location?.uri || this.documentStatus !== status) this.contextMenu.dismiss();
        const title = document.getElementById('workspace')!;
        title.textContent = decodeURIComponent(new URL(this.server.root).pathname);
        this.documentStatus = status;
        this.activeUri = location?.uri;
        await this.updateStatus();
        await this.updateStopped();
        const api = await this.api;
        if (!location) {
            await api.changedCursorLocation(undefined);
            return;
        }
        if (!this.initialized) {
            this.initialized = true;
            await api.initialize(location);
        } else {
            await api.changedCursorLocation(location);
        }
    }

    private async updateStatus(): Promise<void> {
        const { LeanFileProgressKind } = await renderer;
        if (active !== this || bridge.isClosed) return;
        const currentDocument = this.activeUri ? this.documents.get(this.activeUri) : undefined;
        const progress: LeanFileProgressParams | undefined =
            currentDocument?.notifications.get('$/lean/fileProgress')?.params;
        const hasFatalError =
            progress?.processing.some(range => range.kind === LeanFileProgressKind.FatalError) ?? false;
        const status = document.getElementById('status')!;
        const isClosed = this.documentStatus === 'Closed';
        status.hidden = this.documentStatus === 'Synchronized' && !this.server.stopped && !hasFatalError;
        status.classList.toggle('host-error', this.server.stopped != null || hasFatalError || isClosed);
        const waitingMessage = this.server.result ? 'Synchronizing Lean document…' : 'Starting Lean server…';
        let message = waitingMessage;
        if (isClosed) {
            message = `${closedDocument.message} ${closedDocument.reason}`;
        } else if (hasFatalError) {
            message = 'Lean stopped processing this file. Check its diagnostics or use Restart Lean File.';
        }
        status.textContent = this.server.stopped ?? message;
    }

    private async updateStopped(): Promise<void> {
        let reason = this.activeUri ? this.documents.get(this.activeUri)?.stopped : undefined;
        if (this.server.stopped) reason = { message: 'Lean server stopped', reason: this.server.stopped };
        await (await this.api).serverStopped(reason);
        updateActionAvailability();
    }

    private document(uri: string): DocumentState {
        let state = this.documents.get(uri);
        if (!state) {
            state = { notifications: new Map() };
            this.documents.set(uri, state);
        }
        return state;
    }

    async notification(event: Notification): Promise<void> {
        if (
            event.direction === 'Server' &&
            (event.method === 'textDocument/publishDiagnostics' || event.method === '$/lean/fileProgress')
        ) {
            const uri: string = event.params.uri ?? event.params.textDocument.uri;
            const document = this.document(uri);
            const version: number | undefined = event.params.version ?? event.params.textDocument?.version;
            if (version != null && document.version != null && version !== document.version) return;
            document.notifications.set(event.method, event);
            if (uri === this.activeUri && event.method === '$/lean/fileProgress') await this.updateStatus();
        }
        if (
            event.direction === 'Client' &&
            (
                event.method === 'textDocument/didClose' ||
                event.method === 'textDocument/didOpen' ||
                event.method === 'textDocument/didChange'
            )
        ) {
            const uri: string = event.params.textDocument.uri;
            this.contextMenu.dismiss();
            this.documents.delete(uri);
            if (event.method !== 'textDocument/didClose') {
                this.documents.set(uri, { version: event.params.textDocument.version, notifications: new Map() });
            }
            if (uri === this.activeUri) {
                if (event.method === 'textDocument/didClose') this.documentStatus = 'Closed';
                await this.updateStatus();
                await this.updateStopped();
            }
        }
        if ((this.subscriptions[event.direction].get(event.method) ?? 0) > 0) await this.deliver(event);
    }

    private async deliver(event: Notification): Promise<void> {
        const api = await this.api;
        if (event.direction === 'Server') await api.gotServerNotification(event.method, event.params);
        else await api.sentClientNotification(event.method, event.params);
    }

    private async subscribe(direction: 'Server' | 'Client', method: string): Promise<void> {
        const counts = this.subscriptions[direction];
        const count = counts.get(method) ?? 0;
        counts.set(method, count + 1);
        if (count === 0 && direction === 'Server') {
            for (const document of this.documents.values()) {
                const event = document.notifications.get(method);
                if (event) await this.deliver(event);
            }
        }
    }

    private async unsubscribe(direction: 'Server' | 'Client', method: string): Promise<void> {
        const counts = this.subscriptions[direction];
        const count = counts.get(method) ?? 0;
        if (count <= 1) counts.delete(method);
        else counts.set(method, count - 1);
    }

    private async request<T>(uri: string, command: object, signal?: AbortSignal): Promise<T> {
        const document = this.document(uri);
        try {
            return await bridge.call<T>(command, signal);
        } catch (error) {
            const { isRpcError, RpcErrorCode } = await renderer;
            if (
                this.documents.get(uri) === document &&
                isRpcError(error) && error.code === RpcErrorCode.WorkerCrashed
            ) {
                document.stopped = {
                    message: 'Lean has stopped processing this file. Use Restart Lean File to try again.',
                    reason: error.message
                };
                if (uri === this.activeUri) await this.updateStopped();
            }
            throw error;
        }
    }

    private editorApi(): EditorApi {
        return {
            saveConfig: async value => {
                await bridge.call({ type: 'config', config: value });
                config = value;
                for (const workspace of roots.values()) await (await workspace.api).changedInfoviewConfig(value);
            },
            sendClientRequest: (uri, method, params, options) => this.request(uri, {
                type: 'request', serverId: this.server.id, uri, method, params
            }, options?.abortSignal),
            sendClientNotification: (uri, method, params) => bridge.call({
                type: 'notify', serverId: this.server.id, uri, method, params
            }),
            subscribeServerNotifications: method => this.subscribe('Server', method),
            unsubscribeServerNotifications: method => this.unsubscribe('Server', method),
            subscribeClientNotifications: method => this.subscribe('Client', method),
            unsubscribeClientNotifications: method => this.unsubscribe('Client', method),
            copyToClipboard: text => bridge.call({ type: 'clipboard', text }),
            insertText: (text, kind, position) => bridge.call({
                type: 'insert', text, kind: kind === 'here' ? 'Here' : 'Above', position
            }),
            applyEdit: edit => bridge.call({ type: 'edit', serverId: this.server.id, edit }),
            showDocument: location => bridge.call({ type: 'show', serverId: this.server.id, location }),
            restartFile: uri => bridge.call({ type: 'restart', serverId: this.server.id, uri }),
            createRpcSession: uri => this.request(uri, { type: 'connect', serverId: this.server.id, uri }),
            closeRpcSession: sessionId => bridge.call({ type: 'disconnect', sessionId })
        };
    }
}

async function receive(event: Event): Promise<void> {
    if (event.type === 'ready') return;
    if (event.type === 'cursor' && !event.serverId) {
        if (active) {
            active.contextMenu.dismiss();
            active.element.hidden = true;
        }
        active = undefined;
        document.getElementById('workspace')!.textContent = '';
        const status = document.getElementById('status')!;
        status.hidden = false;
        status.classList.remove('host-error');
        status.textContent = 'Open a Lean file to see goals and messages.';
        return;
    }
    if (event.type === 'server') {
        let workspace = roots.get(event.server.root);
        if (!workspace) {
            workspace = new Workspace(event.server);
            roots.set(event.server.root, workspace);
        }
        await workspace.update(event.server);
        return;
    }
    const workspace = [...roots.values()].find(workspace => workspace.server.id === event.serverId);
    if (!workspace) return;
    if (event.type === 'cursor') await workspace.cursor(event.location, event.documentStatus);
    else if (event.type === 'notification') await workspace.notification(event);
}

for (const button of actionButtons) {
    button.addEventListener('click', () => {
        enqueue(() => requestAction({
            kind: button.dataset.action as InfoviewActionKind
        }));
    });
}

void bridge.call<InfoviewConfig | null>({ type: 'ready' }).then(value => {
    config = value ?? undefined;
    if (config) {
        for (const workspace of roots.values()) void workspace.api.then(api => api.changedInfoviewConfig(config!));
    }
}).catch(showError);
