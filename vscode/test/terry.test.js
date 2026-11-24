const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const { SubmissionClient, LoginRequired } = require('../src/submission-client');
const { TerryClient, formInput } = require('../src/terry-client');
const { runTerry } = require('../src/terry-runner');
const { TrainingClient } = require('../src/training');

const task = 'terry/demo';
const formPath = '/task/' + task + '/submit';
const inputId = 'ab120000-0000-4000-8000-000000000001';
const id = 'ab120000-0000-4000-8000-000000000002';
const session = 'better-auth.session_token=demo.value';
const ref = { requestInput: '1'.repeat(40), changeInput: '2'.repeat(40), uploadAndSubmit: '3'.repeat(40) };
const chunk = Object.entries(ref).map(([name, key]) => 'createServerReference)("' + key + '",null,null,"' + name + '")').join(',');
const fixture = name => fs.readFile(path.join(__dirname, '../../shared/fixtures', name + '.html'), 'utf8');
const source = Buffer.from('print("Case #1: 5")\n');
const output = Buffer.from('Case #1: 5\n');

test('Terry sostituisce un input scaduto e carica sorgente e output per il nuovo ID', async () => {
  const ready = await fixture('terry-submit');
  const empty = ready.replace(/\\"input\\":\{[^}]*\}/, '\\"input\\":\\"$undefined\\"');
  let state = ready.replace(/\\"submissionTimeout\\":null/, '\\"submissionTimeout\\":60')
    .replaceAll(inputId, 'ab120000-0000-4000-8000-000000000099');
  const called = [];
  const api = new SubmissionClient(async (url, request) => {
    if (url.pathname === '/_next/static/chunks/terry-demo.js') {
      assert.equal(request.headers.Cookie, 'lang=it-IT');
      return new Response(chunk);
    }
    assert.equal(request.headers.Cookie, 'lang=it-IT; ' + session);
    if (url.pathname === formPath && request.method === 'GET') return new Response(state);
    if (url.pathname === formPath) {
      called.push(request.headers['Next-Action']);
      if (request.headers['Next-Action'] === ref.changeInput) {
        assert.deepEqual(JSON.parse(request.body), ['ab120000-0000-4000-8000-000000000099']);
        state = empty;
      } else if (request.headers['Next-Action'] === ref.requestInput) {
        assert.deepEqual(JSON.parse(request.body), ['demo']);
        state = ready;
      } else {
        const body = await new Response(request.body, { headers: request.headers }).formData();
        assert.deepEqual(JSON.parse(body.get('0')), ['demo', inputId, '$K1', '$K2']);
        assert.equal(body.get('_1_file').name, 'demo.py');
        assert.equal(await body.get('_1_file').text(), source.toString());
        assert.equal(body.get('_2_file').name, 'output.txt');
        assert.equal(await body.get('_2_file').text(), output.toString());
        return new Response(null, { status: 303, headers: { 'x-action-redirect': '/task/' + task + '/submissions/' + id + ';push' } });
      }
      return new Response('0:{"a":"$@1"}\n1:"$undefined"\n');
    }
    if (url.pathname === '/task/' + task + '/submissions/' + id) return new Response(await fixture('terry-result'));
    assert.fail(url.pathname);
  });
  const client = new TerryClient(api, async file => {
    assert.equal(file, '/files/input/ab/12/' + inputId + '/demo_input_1.txt');
    return Buffer.from('1\n5\n');
  });
  const input = await client.input(task, session);
  assert.deepEqual(await client.input(task, session), input);
  assert.equal(await client.submit({ task, source: 'demo.py' }, input, source, output, session), id);
  assert.deepEqual(called, [ref.changeInput, ref.requestInput, ref.uploadAndSubmit]);
  const result = await client.result(task, id, session);
  assert.equal(result.score, 17);
  assert.equal(result.maxScore, 50);
  assert.deepEqual(result.cases.map(row => row.outcome), ['Corretto', 'Errato', 'Non inviato']);
  assert.equal(result.cases[1].detail, 'Valore atteso diverso');
  assert.deepEqual(result.notes, ['Nota: Risposte parziali.']);
});

test('Terry rifiuta input e invii non validi e conserva il punteggio massimo nello storico', async () => {
  const ready = await fixture('terry-submit');
  assert.throws(() => formInput(ready.replaceAll('/ab/12/', '/../12/'), task), /Impossibile leggere/);
  assert.throws(() => formInput('Accedi per inviare soluzioni', task), LoginRequired);
  const api = new SubmissionClient(async url => {
    if (url.pathname.endsWith('.js')) return new Response(chunk);
    if (url.pathname.endsWith('/submissions')) return new Response(
      '<ul><li><a href="/task/' + task + '/submissions/' + id + '"><div>Data</div><div><span class="bg-orange-400">17 / 50</span></div></a></li></ul>');
    assert.fail('Invio oltre il limite');
  });
  const result = await api.results(task, session);
  assert.equal(result.best, 17);
  assert.equal(result.rows[0].maxScore, 50);
  const client = new TerryClient(api);
  await assert.rejects(client.submit({ task, source: 'demo.py' }, { id: inputId, html: ready }, Buffer.alloc(100_000),
    Buffer.alloc(950_000), session), /limite di 1 MiB/);
});

const python = [['py', '-3'], ['python3'], ['python']].some(([program, ...args]) =>
  /^Python 3\./.test(spawnSync(program, [...args, '--version'], { encoding: 'utf8', timeout: 2000 }).stdout || ''));
test('Terry esegue sorgenti con stdin/stdout o input.txt e output.txt', { skip: !python }, async () => {
  const input = { bytes: Buffer.from('1\n5\n') };
  const link = { source: 'demo.py', language: 'py', task };
  const scripts = [
    'input()\nprint("Case #1:", input())\n',
    'from pathlib import Path\nn=Path("input.txt").read_text().splitlines()[1]\nPath("output.txt").write_text("Case #1: " + n)\nprint("debug")\n',
  ];
  for (const script of scripts) {
    const result = await runTerry(link, Buffer.from(script), async () => input);
    assert.equal(result.input, input);
    assert.equal(result.output.toString().trim(), 'Case #1: 5');
  }
  await assert.rejects(runTerry(link, Buffer.from('raise RuntimeError("demo")'), async () => input), /RuntimeError: demo/);
});

test('catalogo e template Territoriali reali', { skip: !process.env.OLINFO_LIVE_TEST }, async () => {
  const client = new TrainingClient();
  const catalog = await client.search('terry/download', 1, true);
  assert.equal(catalog.tasks[0].id, 'terry/download');
  const details = await client.details('terry/download');
  assert.equal(details.mode, 'terry');
  assert.match(details.statement.toString(), /Filmati|filmati/);
  assert.equal(details.options.find(item => item.id === 'cpp').starter.name, 'download.cpp');
  assert.match((await client.asset(details.files.find(file => file.name === 'output.txt'))).toString(), /Case #1:/);
  const newer = await client.details('terry/escursione');
  assert.match((await client.asset(newer.files.find(file => file.name === 'input.txt'))).toString(), /^3\r?\n/);
});
