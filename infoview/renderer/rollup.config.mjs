import commonjs from '@rollup/plugin-commonjs';
import json from '@rollup/plugin-json';
import nodeResolve from '@rollup/plugin-node-resolve';
import replace from '@rollup/plugin-replace';
import terser from '@rollup/plugin-terser';
import typescript from '@rollup/plugin-typescript';
import url from '@rollup/plugin-url';
import path from 'node:path';
import css from 'rollup-plugin-css-only';

const output = {
    dir: path.join(import.meta.dirname, 'dist'),
    format: 'esm',
    compact: true,
    entryFileNames: '[name].production.min.js',
    chunkFileNames: '[name]-[hash].production.min.js',
    plugins: [terser()]
};

function plugins() {
    return [
        url({ include: ['**/*.ttf'], fileName: '[name][extname]' }),
        typescript({ tsconfig: path.join(import.meta.dirname, 'tsconfig.json') }),
        nodeResolve({ browser: true }),
        replace({ 'process.env.NODE_ENV': JSON.stringify('production'), preventAssignment: true }),
        commonjs(),
        json()
    ];
}

// Widgets share these modules through an import map. Each entry must remain independently loadable.
export default [
    {
        input: path.join(import.meta.dirname, 'src/index.tsx'),
        external: ['react', 'react-dom', 'react/jsx-runtime'],
        plugins: [...plugins(), css({ output: 'index.css' })],
        output
    },
    {
        input: path.join(import.meta.dirname, 'src/loader.ts'),
        plugins: plugins(),
        output: {
            ...output,
            intro: 'window.esmsInitOptions = { shimMode: true }'
        }
    },
    ...['react', 'react-dom', 'react-jsx-runtime'].map(name => ({
        input: path.join(import.meta.dirname, 'src/esm-shims', `${name}.ts`),
        external: name === 'react' ? [] : ['react'],
        plugins: plugins(),
        output
    }))
];
