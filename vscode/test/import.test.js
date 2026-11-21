const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { strToU8, zipSync } = require('fflate');
const { TrainingClient } = require('../src/training');
const { createProject } = require('../src/project');

const fixtures = path.join(__dirname, '../../shared/fixtures');
const bytes = text => Buffer.from(text);
const zip = zipSync({
  'starter/demo_grader.cpp': strToU8('int add(int a, int b) { return 0; }\n'),
  'grader/grader.cpp': strToU8('int main() {}\n'),
  'input/input10.txt': strToU8('10 10\n'),
  'output/output10.txt': strToU8('20\n'),
  'input/input2.txt': strToU8('2 2\n'),
  'output/output2.txt': strToU8('4\n'),
  'private-solution.py': strToU8('raise RuntimeError()\n'),
});

async function temporary(t) {
  const folder = await fs.mkdtemp(path.join(os.tmpdir(), 'olinfo-vscode-'));
  t.after(() => fs.rm(folder, { recursive: true, force: true }));
  return folder;
}

async function mockClient() {
  const catalog = await fs.readFile(path.join(fixtures, 'catalog.html'));
  const problem = await fs.readFile(path.join(fixtures, 'problem.html'), 'utf8');
  return new TrainingClient(async (url, options) => {
    if (url.pathname === '/tasks/1') {
      assert.equal(url.searchParams.get('search'), 'demo & prova');
      return new Response(catalog);
    }
    if (url.pathname === '/task/demo_grader') {
      return new Response(options.headers.Cookie === 'lang=en-GB'
        ? problem.replace('lang="it-IT"', 'lang="en-GB"').replace(/b{40}/g, 'c'.repeat(40))
        : problem);
    }
    if (url.pathname.endsWith('/attachments.zip')) return new Response(zip);
    if (url.pathname.includes('c'.repeat(40))) return new Response('%PDF-English');
    if (url.pathname.includes('b'.repeat(40))) return new Response('%PDF-Italiano');
    return new Response(null, { status: 404 });
  });
}

test('catalogo, archivio, traduzione e generazione senza sovrascrittura', async t => {
  const client = await mockClient();
  const found = await client.search('demo & prova');
  assert.deepEqual(found.tasks, [
    { id: 'demo_grader', title: 'Somma & differenza' },
    { id: 'demo_stdio', title: 'Programma autonomo' },
  ]);
  assert.equal(found.hasNext, true);

  const details = await client.details('demo_grader');
  assert.deepEqual(details.pdf.map(option => option.id), ['it-IT', 'en-GB']);
  assert.deepEqual(details.options.map(option => option.id), ['cpp']);
  const folder = await temporary(t);
  const { target, solutionName } = await createProject(client, details, 'cpp', 'en-GB', folder, 'Demo progetto');
  assert.equal(solutionName, 'file.cpp');
  assert.deepEqual((await fs.readdir(target)).sort(), ['CMakeLists.txt', 'file.cpp', 'grader.cpp', 'input.txt', 'output.txt', 'testo.pdf']);
  assert.equal(await fs.readFile(path.join(target, 'input.txt'), 'utf8'), '2 2\n');
  assert.equal(await fs.readFile(path.join(target, 'output.txt'), 'utf8'), '4\n');
  assert.equal(await fs.readFile(path.join(target, 'testo.pdf'), 'utf8'), '%PDF-English');
  await assert.rejects(createProject(client, details, 'cpp', 'it-IT', folder, 'Demo progetto'), /Esiste già/);
  await assert.rejects(createProject(client, details, 'cpp', 'it-IT', folder, '../escape'), /nome cartella/);
  await assert.rejects(createProject(client, details, 'cpp', 'de-DE', folder, 'NoFiles'), /Traduzione non disponibile/);
  assert.deepEqual(await fs.readdir(folder), ['Demo progetto']);
});

test('programma autonomo senza allegati con file di I/O vuoti', async t => {
  const problem = (await fs.readFile(path.join(fixtures, 'problem.html'), 'utf8'))
    .replace('>grader<', '>stdin / stdout<').replace(/<aside>[\s\S]*?<\/aside>/, '');
  const client = new TrainingClient(async url => new Response(url.pathname.endsWith('/testo.pdf') ? bytes('%PDF-IT') : problem));
  const details = await client.details('demo_stdio');
  const folder = await temporary(t);
  const { target } = await createProject(client, details, 'py', 'it-IT', folder, 'Python');
  assert.deepEqual((await fs.readdir(target)).sort(), ['file.py', 'input.txt', 'output.txt', 'testo.pdf']);
  assert.equal((await fs.stat(path.join(target, 'input.txt'))).size, 0);
  assert.equal((await fs.stat(path.join(target, 'output.txt'))).size, 0);

  const cpp = await createProject(client, details, 'cpp', 'it-IT', folder, 'Nome del progetto');
  assert.equal(cpp.solutionName, 'demo_stdio.cpp');
  assert.deepEqual((await fs.readdir(cpp.target)).sort(), ['CMakeLists.txt', 'demo_stdio.cpp', 'input.txt', 'output.txt', 'testo.pdf']);
  const source = await fs.readFile(path.join(cpp.target, cpp.solutionName), 'utf8');
  assert.match(source, /^#include <bits\/stdc\+\+\.h>\nusing namespace std;\n\nint main\(\) \{/);
  assert.match(await fs.readFile(path.join(cpp.target, 'CMakeLists.txt'), 'utf8'), /add_executable\(solution "demo_stdio\.cpp"\)/);
});

test('catalogo reale, PDF italiano e inglese, grader e ZIP', { skip: process.env.OLINFO_LIVE_TEST !== '1' }, async t => {
  const client = new TrainingClient();
  assert.ok((await client.search('oii_datazione')).tasks.some(task => task.id === 'oii_datazione'));
  const graderTask = await client.details('oii_datazione');
  assert.deepEqual(graderTask.options.map(item => item.id), ['cpp']);
  assert.notDeepEqual(await client.pdf(graderTask, 'it-IT'), await client.pdf(graderTask, 'en-GB'));
  const folder = await temporary(t);
  const first = await createProject(client, graderTask, 'cpp', 'en-GB', folder, 'grado');
  assert.ok((await fs.stat(path.join(first.target, 'grader.cpp'))).size > 0);
  assert.ok((await fs.stat(path.join(first.target, 'input.txt'))).size > 0);
  const program = await createProject(client, await client.details('easy2'), 'cpp', 'it-IT', folder, 'programma');
  assert.equal(program.solutionName, 'easy2.cpp');
  assert.ok((await fs.stat(path.join(program.target, 'input.txt'))).size > 0);
  assert.ok((await fs.stat(path.join(program.target, 'output.txt'))).size > 0);
  await assert.rejects(fs.stat(path.join(program.target, 'grader.cpp')), { code: 'ENOENT' });
  const missing = await client.details('oii_z');
  assert.equal(missing.options.find(item => item.id === 'cpp').grader, undefined);
});
