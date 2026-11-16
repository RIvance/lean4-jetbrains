import type { InfoviewAction } from '@leanprover/infoview-api';

export interface ProtocolError {
    code: number;
    message: string;
    data?: unknown;
}

type Reply = { id: string } & (
    { type: 'success'; result: unknown } |
    { type: 'error'; code: number; message: string; data?: unknown }
);

export interface HostMessage {
    event?: unknown; reply?: Reply; theme?: Record<string, string>; action?: InfoviewAction;
    closed?: string;
}

declare global {
    interface Window {
        leanHost?: (message: string) => void;
        leanReceive?: (message: HostMessage) => void;
    }
}

/** One pending request belongs to this page lifetime. Reload/disposal rejects all of them. */
export class HostBridge {
    private nextId = 0;
    private failure?: ProtocolError;
    private readonly lifetime = new AbortController();
    private readonly pending = new Map<string, { resolve(value: unknown): void; reject(error: unknown): void }>();

    get isClosed(): boolean { return this.failure !== undefined; }

    constructor(
        private readonly event: (event: unknown) => void,
        private readonly action: (action: InfoviewAction) => void,
        private readonly disconnected: (error: ProtocolError) => void
    ) {
        window.leanReceive = message => {
            if (message.closed) this.close({ code: -32800, message: message.closed });
            if (this.isClosed) return;
            if (message.theme) {
                for (const [property, value] of Object.entries(message.theme)) {
                    document.documentElement.style.setProperty(property, value);
                }
            }
            if (message.event) this.event(message.event);
            if (message.action) this.action(message.action);
            if (message.reply) {
                const reply = message.reply;
                const pending = this.pending.get(reply.id);
                if (!pending) return;
                this.pending.delete(reply.id);
                if (reply.type === 'success') {
                    pending.resolve(reply.result);
                } else {
                    pending.reject(
                        { code: reply.code, message: reply.message, data: reply.data } satisfies ProtocolError
                    );
                }
            }
        };
        window.addEventListener('pagehide', () => {
            this.close({ code: -32800, message: 'InfoView closed' });
        }, { once: true });
    }

    private close(error: ProtocolError): void {
        if (this.isClosed) return;
        this.failure = error;
        this.lifetime.abort();
        for (const pending of this.pending.values()) pending.reject(error);
        this.pending.clear();
        this.disconnected(error);
    }

    async ready(): Promise<void> {
        if (this.failure) throw this.failure;
        if (window.leanHost) return;
        await new Promise<void>((resolve, reject) => {
            const signal = this.lifetime.signal;
            const closed = () => reject(this.failure);
            signal.addEventListener('abort', closed, { once: true });
            window.addEventListener('lean-host-ready', () => {
                signal.removeEventListener('abort', closed);
                resolve();
            }, { once: true, signal });
        });
    }

    async call<T>(command: object, signal?: AbortSignal): Promise<T> {
        await this.ready();
        if (this.failure) throw this.failure;
        const id = String(++this.nextId);
        const cancel = () => {
            if (!this.isClosed) window.leanHost!(JSON.stringify({ id, command: { type: 'cancel' } }));
        };
        const promise = new Promise<unknown>((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            try {
                window.leanHost!(JSON.stringify({ id, command }));
            } catch (error) {
                this.pending.delete(id);
                reject(error);
            }
        });
        signal?.addEventListener('abort', cancel, { once: true });
        if (signal?.aborted) cancel();
        try {
            return await promise as T;
        } finally {
            signal?.removeEventListener('abort', cancel);
        }
    }
}

