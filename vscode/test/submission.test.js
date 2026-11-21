const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { projectXml, readProject, readSource } = require('../src/project-link');
const { SubmissionClient, LoginRequired, submissionList, submitOptions } = require('../src/submission-client');

const fixture = file => fs.readFile(path.join(__dirname, '../../shared/fixtures', file), 'utf8');
const cookie = '__Secure-better-auth.session_token=mock-token.test%2Bsignature';

test('i metadati indicano il sorgente e rifiutano percorsi al di fuori del progetto', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'olinfo-manifest-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const manifest = path.join(root, 'olinfo.xml');
  await fs.writeFile(manifest, projectXml({ task: 'demo', language: 'cpp', source: 'demo.cpp' }));
  await fs.writeFile(path.join(root, 'demo.cpp'), 'int main() {}');
  const link = await readProject(root);
  assert.equal((await readSource(link)).toString(), 'int main() {}');
  await fs.writeFile(manifest, '<olinfo version="1" task="demo" language="cpp" source="../demo.cpp"/>');
  await assert.rejects(readProject(root), /file della soluzione/);
});

test('accesso, multipart e valutazione rispettano il protocollo di Training', async () => {
  const [form, history] = await Promise.all([fixture('submit.html'), fixture('submissions.html')]);
  const action = 'a'.repeat(42);
  let uploads = 0;
  const client = new SubmissionClient(async (url, options) => {
    assert.equal(url.origin, 'https://training.olinfo.it');
    if (url.pathname === '/login' && options.method === 'GET') {
      return new Response('<script src="/_next/static/chunks/login.js"></script>');
    }
    if (url.pathname.endsWith('/login.js')) {
      return new Response('let u=(0,d.createServerReference)("' + action + '",d.callServer,void 0,d.findSourceMapURL,"loginPassword");');
    }
    if (url.pathname === '/login') {
      assert.equal(options.headers['Next-Action'], action);
      assert.deepEqual(JSON.parse(options.body), ['demo', '$$mock-password']);
      return new Response('0:{"a":"$undefined"}', { headers: { 'Set-Cookie': cookie + '; HttpOnly; Secure' } });
    }
    assert.match(options.headers.Cookie, /session_token=mock-token/);
    if (url.pathname.endsWith('/submit') && options.method === 'GET') return new Response(form);
    if (url.pathname.endsWith('/submissions')) return new Response(history);
    assert.equal(url.pathname, '/files/submit');
    assert.equal(url.searchParams.get('language'), 'C++20');
    assert.equal(url.searchParams.get('task'), 'demo');
    const source = options.body.get('demo.%l');
    assert.equal(source.name, 'demo.cpp');
    assert.equal(await source.text(), 'int main() {}');
    ++uploads;
    return Response.json({ submissionId: 4 });
  });
  const session = await client.login('demo', '$mock-password');
  assert.equal(session, cookie);
  await assert.rejects(client.results('demo'), LoginRequired);
  const id = await client.submit({ task: 'demo', language: 'cpp', source: 'demo.cpp' }, Buffer.from('int main() {}'), session);
  const result = await client.results('demo', session);
  assert.equal(id, '4');
  assert.equal(uploads, 1);
  assert.equal(result.best, 73);
  assert.equal(result.rows[0].pending, true);
  assert.equal(result.rows[1].score, 0);
  assert.equal(result.rows[3].pending, false);
});

test('riconosce sessione scaduta, linee guida e invii vuoti', async () => {
  assert.throws(() => submissionList('<div>Accedi per vedere le tue sottoposizioni</div>', 'demo'), LoginRequired);
  assert.equal(submissionList('<div>Nessuna sottoposizione inviata</div>', 'demo').best, undefined);
  const form = await fixture('submit.html');
  assert.throws(() => submitOptions(form.replace('Accepted\\":true', 'Accepted\\":false'), 'cpp'), /linee guida/);
});
