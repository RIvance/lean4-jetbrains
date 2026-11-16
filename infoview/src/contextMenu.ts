import type { ContextMenuEntry, InfoviewApi } from '@leanprover/infoview-api';

interface Entry { action: ContextMenuEntry; label: string; key?: string; settings?: boolean }
interface ContextValue { value: unknown; owner: Element }

// The upstream renderer publishes these action IDs in data-vscode-context. Keep presentation policy here.
const entries: Entry[] = [
    { action: 'goToDefinition', label: 'Go to definition', key: 'interactiveCodeTagId' },
    { action: 'goToPinnedLocation', label: 'Go to pinned location' },
    { action: 'goToMessageLocation', label: 'Go to message source' },
    { action: 'showTraceSearch', label: 'Search trace' },
    { action: 'hideTraceSearch', label: 'Hide trace search' },
    { action: 'select', label: 'Select', key: 'selectableLocationId' },
    { action: 'unselect', label: 'Unselect', key: 'unselectableLocationId' },
    { action: 'unselectAll', label: 'Unselect all', key: 'selectedLocationsId' },
    { action: 'pin', label: 'Pin state' },
    { action: 'unpin', label: 'Unpin state' },
    { action: 'pause', label: 'Pause state' },
    { action: 'unpause', label: 'Resume state' },
    { action: 'refresh', label: 'Refresh paused state' },
    { action: 'pauseAllMessages', label: 'Pause all messages' },
    { action: 'unpauseAllMessages', label: 'Resume all messages' },
    { action: 'copyState', label: 'Copy state' },
    { action: 'copyMessage', label: 'Copy message' },
    { action: 'displayTargetBeforeAssumptions', label: 'Show target first', settings: true },
    { action: 'displayAssumptionsBeforeTarget', label: 'Show assumptions first', settings: true },
    { action: 'hideTypeAssumptions', label: 'Hide type assumptions', settings: true },
    { action: 'showTypeAssumptions', label: 'Show type assumptions', settings: true },
    { action: 'hideInstanceAssumptions', label: 'Hide instance assumptions', settings: true },
    { action: 'showInstanceAssumptions', label: 'Show instance assumptions', settings: true },
    { action: 'hideInaccessibleAssumptions', label: 'Hide inaccessible assumptions', settings: true },
    { action: 'showInaccessibleAssumptions', label: 'Show inaccessible assumptions', settings: true },
    { action: 'hideLetValues', label: 'Hide let values', settings: true },
    { action: 'showLetValues', label: 'Show let values', settings: true },
    { action: 'hideGoalNames', label: 'Hide goal names', settings: true },
    { action: 'showGoalNames', label: 'Show goal names', settings: true },
    { action: 'emphasizeFirstGoal', label: 'Emphasize first goal', settings: true },
    { action: 'deemphasizeFirstGoal', label: 'Stop emphasizing first goal', settings: true },
    { action: 'saveSettings', label: 'Save these display settings', settings: true }
];

function contextAt(target: Element, root: HTMLElement): Map<string, ContextValue> {
    const ancestry: Element[] = [];
    for (let element: Element | null = target; element; element = element.parentElement) {
        ancestry.unshift(element);
        if (element === root) break;
    }
    const context = new Map<string, ContextValue>();
    for (const element of ancestry) {
        const source = element.getAttribute('data-vscode-context');
        if (!source) continue;
        try {
            const value: unknown = JSON.parse(source);
            if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
                for (const [key, item] of Object.entries(value)) context.set(key, { value: item, owner: element });
            }
        } catch {
            // Ignore malformed widget metadata while retaining valid actions from its ancestors.
        }
    }
    return context;
}

/** The workspace owns this controller; each open menu owns its transient document listeners and focus. */
export class InfoviewContextMenu {
    private readonly lifetime = new AbortController();
    private closeMenu?: (restoreFocus: boolean) => void;
    private generation = 0;

    constructor(
        private readonly root: HTMLElement,
        private readonly api: Promise<InfoviewApi>,
        private readonly available: () => boolean,
        private readonly failed: (error: unknown) => void
    ) {
        root.addEventListener('contextmenu', event => {
            if (event.target instanceof Element && this.open(event.target, event.clientX, event.clientY)) {
                event.preventDefault();
                event.stopPropagation();
            }
        }, { signal: this.lifetime.signal });
        root.addEventListener('keydown', event => {
            const menuKey = event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey);
            if (!menuKey || event.altKey || event.ctrlKey || event.metaKey || event.defaultPrevented) return;
            if (!(event.target instanceof Element)) return;
            const anchor = event.target.getBoundingClientRect();
            if (this.open(event.target, anchor.left, anchor.bottom)) {
                event.preventDefault();
                event.stopPropagation();
            }
        }, { signal: this.lifetime.signal });
    }

    /** Invalidate both the visible menu and an activation waiting for the renderer's API. */
    dismiss(): void {
        this.generation++;
        this.closeMenu?.(false);
    }

    dispose(): void {
        this.lifetime.abort();
        this.dismiss();
    }

    private usable(target: Element): boolean {
        return (
            !this.lifetime.signal.aborted && this.available() && this.root.isConnected &&
            this.root.contains(target) && target.closest('[hidden], [inert]') === null
        );
    }

    private open(target: Element, x: number, y: number): boolean {
        this.dismiss();
        if (!this.usable(target)) return false;
        const context = contextAt(target, this.root);
        const available = entries.flatMap(entry => {
            const source = context.get(entry.key ?? `${entry.action}Id`);
            if (typeof source?.value !== 'string' || source.value === '') return [];
            return [{ ...entry, id: source.value, owner: source.owner }];
        });
        if (available.length === 0) return false;
        const generation = this.generation;
        const previousFocus = document.activeElement;
        const lifetime = new AbortController();
        const menu = document.createElement('div');
        menu.className = 'host-menu';
        menu.role = 'menu';
        menu.setAttribute('aria-label', 'Lean InfoView');
        const observer = new MutationObserver(records => {
            const changed = records.some(record => {
                if (record.type === 'attributes') {
                    return record.target instanceof Element && record.target.contains(target);
                }
                return (
                    available.some(entry => entry.owner.contains(record.target)) ||
                    [...record.removedNodes].some(node => node.contains(target))
                );
            });
            if (!this.usable(target) || changed) this.dismiss();
        });
        this.closeMenu = restoreFocus => {
            lifetime.abort();
            observer.disconnect();
            menu.remove();
            this.closeMenu = undefined;
            if (
                restoreFocus && previousFocus instanceof HTMLElement && previousFocus.isConnected &&
                previousFocus.closest('[hidden], [inert]') === null
            ) previousFocus.focus();
        };
        let settingsStarted = false;
        for (const entry of available) {
            if (entry.settings && !settingsStarted) {
                const separator = document.createElement('hr');
                separator.role = 'separator';
                menu.append(separator);
                settingsStarted = true;
            }
            const item = document.createElement('button');
            item.role = 'menuitem';
            item.tabIndex = -1;
            item.textContent = entry.label;
            item.addEventListener('click', () => {
                this.closeMenu?.(true);
                void this.api.then(async api => {
                    if (generation !== this.generation || !this.usable(target)) return;
                    const current = contextAt(target, this.root).get(entry.key ?? `${entry.action}Id`);
                    if (current?.value !== entry.id || current.owner !== entry.owner) return;
                    await api.clickedContextMenu({ entry: entry.action, id: entry.id });
                }).catch(error => {
                    if (generation === this.generation && !this.lifetime.signal.aborted) this.failed(error);
                });
            }, { signal: lifetime.signal });
            menu.append(item);
        }
        document.body.append(menu);
        menu.style.left = `${Math.max(0, Math.min(x, innerWidth - menu.offsetWidth))}px`;
        menu.style.top = `${Math.max(0, Math.min(y, innerHeight - menu.offsetHeight))}px`;
        const buttons = [...menu.querySelectorAll('button')];
        buttons[0].focus();
        menu.addEventListener('keydown', event => {
            if (event.key === 'Escape' || event.key === 'Tab') {
                this.closeMenu?.(true);
                if (event.key === 'Escape') event.preventDefault();
                event.stopPropagation();
                return;
            }
            const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
            if (event.key === 'ArrowDown') buttons[(index + 1) % buttons.length].focus();
            else if (event.key === 'ArrowUp') buttons[(index - 1 + buttons.length) % buttons.length].focus();
            else if (event.key === 'Home') buttons[0].focus();
            else if (event.key === 'End') buttons[buttons.length - 1].focus();
            else return;
            event.preventDefault();
            event.stopPropagation();
        }, { signal: lifetime.signal });
        document.addEventListener('pointerdown', event => {
            if (!menu.contains(event.target as Node)) this.dismiss();
        }, { signal: lifetime.signal, capture: true });
        document.addEventListener('focusin', event => {
            if (!menu.contains(event.target as Node)) this.dismiss();
        }, { signal: lifetime.signal });
        document.addEventListener('scroll', event => {
            if (!menu.contains(event.target as Node)) this.dismiss();
        }, { signal: lifetime.signal, capture: true });
        window.addEventListener('resize', () => this.dismiss(), { signal: lifetime.signal });
        window.addEventListener('blur', () => this.dismiss(), { signal: lifetime.signal });
        observer.observe(this.root, {
            subtree: true, childList: true, characterData: true,
            attributes: true, attributeFilter: ['data-vscode-context', 'hidden', 'inert']
        });
        for (let parent = this.root.parentElement; parent; parent = parent.parentElement) {
            observer.observe(parent, { childList: true, attributes: true, attributeFilter: ['hidden', 'inert'] });
        }
        return true;
    }
}
