import { cp, mkdir, readFile, readdir, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { rollup } from 'rollup';
import rendererConfigurations from './renderer/rollup.config.mjs';

await rm('dist', { recursive: true, force: true });
await rm('renderer/dist', { recursive: true, force: true });
for (const configuration of rendererConfigurations) {
    const bundle = await rollup(configuration);
    try {
        await bundle.write(configuration.output);
    } finally {
        await bundle.close();
    }
}

const compiler = JSON.parse(await readFile('node_modules/typescript/package.json', 'utf8'));
execFileSync(process.execPath, [path.join('node_modules/typescript', compiler.bin.tsc)], { stdio: 'inherit' });
await mkdir('dist/vendor', { recursive: true });
for (const file of await readdir('renderer/dist')) {
    if (file.endsWith('.production.min.js') || file.endsWith('.css') || file.endsWith('.ttf')) {
        await cp(path.join('renderer/dist', file), path.join('dist/vendor', file));
    }
}
await cp('public', 'dist', { recursive: true });
await cp('licenses', 'dist/licenses', { recursive: true });

const lock = JSON.parse(await readFile('package-lock.json', 'utf8'));
for (const [directory, dependency] of Object.entries(lock.packages).sort()) {
    if (!directory.split('/').includes('node_modules') || dependency.dev) continue;
    const destination = path.join('dist/licenses/dependencies', directory);
    for (const entry of await readdir(directory, { withFileTypes: true })) {
        if (entry.isFile() && /^(licen[cs]e|copying|notice|thirdpartynotices)/i.test(entry.name)) {
            await mkdir(destination, { recursive: true });
            await cp(path.join(directory, entry.name), path.join(destination, entry.name));
        }
    }
}
