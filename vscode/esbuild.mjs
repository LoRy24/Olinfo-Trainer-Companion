import * as esbuild from 'esbuild';
import { copyFile, cp, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';

await mkdir('dist', { recursive: true });
await copyFile('../LICENSE', 'LICENSE');
await cp('../assets/oii', 'dist/oii', { recursive: true });

const licenses = {
  name: 'third-party-licenses',
  setup(build) {
    build.onEnd(async result => {
      if (!result.metafile) return;
      const packages = new Set();
      for (const file of Object.keys(result.metafile.inputs)) {
        const match = file.match(/^(.*node_modules\/(?:@[^/]+\/)?[^/]+)\//);
        if (match) packages.add(match[1]);
      }
      const notices = await Promise.all([...packages].sort().map(async directory => {
        const metadata = JSON.parse(await readFile(directory + '/package.json', 'utf8'));
        const filename = (await readdir(directory)).find(file => /^(?:licen[cs]e|copying)(?:\..+)?$/i.test(file));
        const author = metadata.author?.name || metadata.author || '';
        const license = filename
          ? await readFile(directory + '/' + filename, 'utf8')
          : [metadata.license, author].filter(Boolean).join('\n');
        return metadata.name + ' ' + metadata.version + '\n\n' + license.trim();
      }));
      await writeFile('dist/THIRD_PARTY_NOTICES.txt', notices.join('\n\n' + '-'.repeat(60) + '\n\n') + '\n');
    });
  },
};

const options = {
  entryPoints: ['src/extension.js'],
  bundle: true,
  platform: 'node',
  target: 'node20',
  outfile: 'dist/extension.js',
  external: ['vscode'],
  define: { CATALOG_PROPERTIES: JSON.stringify(await readFile('../shared/catalog.properties', 'utf8')) },
  sourcemap: true,
  metafile: true,
  plugins: [licenses],
  legalComments: 'external',
};

if (process.argv.includes('--watch')) {
  const context = await esbuild.context(options);
  await context.watch();
} else {
  await esbuild.build(options);
}
