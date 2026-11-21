const { load } = require('cheerio/slim');
const { taskId } = require('./project-link');
const { accountUsername, profile, rankFor } = require('./account');

const origin = 'https://training.olinfo.it';
const sessionCookie = /^(?:__Secure-)?better-auth\.session_token=[A-Za-z0-9._%+~\/=-]+$/;
const actionReference = /createServerReference[^\w"]{0,12}"([a-f0-9]{40,64})"[^;]{0,180}?"loginPassword"/;
const maxBytes = 4 * 1024 * 1024;

class LoginRequired extends Error {
  constructor() { super('Accedi a Training Olinfo per continuare.'); }
}

function pathFor(task, suffix) {
  if (!taskId.test(task)) throw new Error('Identificativo del problema non valido.');
  return '/task/' + task + '/' + suffix;
}

function loginScripts(html) {
  const $ = load(html);
  return [...new Set($('script[src]').map((_, script) => $(script).attr('src')).get()
    .filter(src => /^\/_next\/static\/chunks\/[A-Za-z0-9_~.-]+\.js$/.test(src)))].slice(0, 24);
}

function flight(html) {
  const $ = load(html);
  return $('script').map((_, script) => {
    const segments = [];
    for (const match of $(script).text().matchAll(/self\.__next_f\.push\(\[1,("(?:\\.|[^"\\])*")\]\)/g)) {
      segments.push(JSON.parse(match[1]));
    }
    return segments.join('');
  }).get().join('');
}

function submitOptions(html, language) {
  const data = flight(html);
  const document = load(html);
  document('script').remove();
  if (/Accedi per inviare soluzioni/i.test(document.root().text())) throw new LoginRequired();
  const files = data.match(/"submissionFormat":(\[[^\]]*\])/);
  const languages = data.match(/"languages":(\{[^{}]*\})/);
  if (!files || !languages) throw new Error('Il modulo di invio non è disponibile sul sito per questo problema.');
  if (!data.includes('"guidelinesAccepted":true')) {
    throw new Error('Accetta le linee guida su training.olinfo.it/guidelines prima del primo invio.');
  }
  const fields = JSON.parse(files[1]);
  if (fields.length !== 1 || typeof fields[0] !== 'string' || !/^[\w.%+-]+$/.test(fields[0])) {
    throw new Error('Apri il problema sul sito per inviare i file richiesti.');
  }
  const compiler = Object.entries(JSON.parse(languages[1])).find(([, code]) =>
    code === (language === 'pas' ? 'pascal' : language))?.[0];
  if (!compiler) throw new Error('Training Olinfo non accetta questo linguaggio per il problema.');
  return { field: fields[0], compiler };
}

function submissionList(html, task) {
  const $ = load(html);
  const prefix = pathFor(task, 'submissions/');
  const rows = [];
  for (const node of $('li > a[href]')) {
    const href = $(node).attr('href');
    if (!href.startsWith(prefix)) continue;
    const id = href.slice(prefix.length);
    if (!/^[1-9]\d*$/.test(id)) continue;
    const cell = $(node).children().last();
    const outcome = cell.text().trim();
    if (!outcome) continue;
    const score = /^\d{1,9}$/.test(outcome) ? Number(outcome) : undefined;
    const color = (cell.find('span').first().attr('class') || '').match(/\bbg-(red|orange|yellow|lime|green)-400\b/)?.[1];
    rows.push({ id, outcome, score, color, pending: /in corso/i.test(outcome) });
  }
  if (!rows.length) {
    $('script').remove();
    const visible = $.root().text();
    if (/Accedi per vedere le tue sottoposizioni/i.test(visible)) throw new LoginRequired();
    if (!/Nessuna sottoposizione inviata/i.test(visible)) {
      throw new Error('Training Olinfo ha restituito un elenco di invii in un formato imprevisto.');
    }
  }
  const scores = rows.flatMap(row => row.score === undefined ? [] : [row.score]);
  return { rows, best: scores.length ? Math.max(...scores) : undefined };
}

class SubmissionClient {
  constructor(request = fetch) { this.request = request; }

  async exchange(location, { session = '', headers = {}, body } = {}) {
    const url = new URL(location, origin);
    if (url.origin !== origin || (session && !sessionCookie.test(session))) throw new LoginRequired();
    let response;
    try {
      response = await this.request(url, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'Accept-Language': 'it-IT', 'Cache-Control': 'no-cache', Origin: origin,
          Cookie: session ? 'lang=it-IT; ' + session : 'lang=it-IT', ...headers },
        body, redirect: 'manual', signal: AbortSignal.timeout(18_000),
      });
      const reader = response.body?.getReader();
      const chunks = [];
      let length = 0;
      if (reader) {
        try {
          for (let part = await reader.read(); !part.done; part = await reader.read()) {
            length += part.value.length;
            if (length > maxBytes) throw new Error('Risposta troppo grande.');
            chunks.push(part.value);
          }
        } finally {
          await reader.cancel().catch(() => {});
        }
      }
      return { response, text: Buffer.concat(chunks, length).toString('utf8') };
    } catch {
      throw new Error('Impossibile leggere la risposta di Training Olinfo.');
    }
  }

  async page(location, session) {
    const { response, text } = await this.exchange(location, { session });
    if ((response.status >= 300 && response.status < 400 && /^\/login(?:[?/]|$)/.test(response.headers.get('location') ?? ''))
        || response.status === 401) throw new LoginRequired();
    if (!response.ok) throw new Error('Training Olinfo ha risposto con HTTP ' + response.status + '.');
    return text;
  }

  async login(username, password) {
    const chunks = loginScripts(await this.page('/login'));
    let action;
    for (let index = 0; index < chunks.length && !action; index += 4) {
      const batch = await Promise.allSettled(chunks.slice(index, index + 4).map(chunk => this.page(chunk)));
      action = batch.flatMap(result => result.status === 'fulfilled' ? [result.value.match(actionReference)?.[1]] : []).find(Boolean);
    }
    if (!action) throw new Error('Il modulo di accesso di Training Olinfo è cambiato.');
    const { response } = await this.exchange('/login', {
      headers: { 'Next-Action': action, 'Content-Type': 'text/plain;charset=UTF-8', Accept: 'text/x-component' },
      // React Flight quotes leading dollar signs in literal string arguments.
      body: JSON.stringify([username, password].map(value => value.startsWith('$') ? '$' + value : value)),
    });
    const location = response.headers.get('x-action-redirect') || response.headers.get('location') || '';
    if (location.startsWith('/signup')) throw new Error('Completa la registrazione sul sito di Training Olinfo.');
    const cookie = response.headers.getSetCookie().map(line => line.split(';', 1)[0]).find(line => sessionCookie.test(line));
    if (!response.ok || !cookie) throw new Error('Accesso non riuscito. Verifica username o email e password.');
    return cookie;
  }

  async results(task, session) {
    if (!session) throw new LoginRequired();
    return submissionList(await this.page(pathFor(task, 'submissions'), session), task);
  }

  async username(session) {
    if (!session) throw new LoginRequired();
    const username = accountUsername(await this.page('/', session));
    if (!username) throw new LoginRequired();
    return username;
  }

  async profile(username) {
    return profile(await this.page('/user/' + encodeURIComponent(username)));
  }

  async rank(username, points) {
    return rankFor(location => this.page(location), username, points);
  }

  async submit(link, source, session) {
    if (!session) throw new LoginRequired();
    const options = submitOptions(await this.page(pathFor(link.task, 'submit'), session), link.language);
    const body = new FormData();
    body.append(options.field, new Blob([source], { type: 'application/octet-stream' }), link.source);
    const query = new URLSearchParams({ task: link.task, language: options.compiler });
    let data;
    try {
      const { response, text } = await this.exchange('/files/submit?' + query, { session, body });
      if (!response.ok) throw new Error();
      data = JSON.parse(text);
    } catch {
      throw new Error('Esito dell’invio incerto. Controlla gli invii sul sito prima di riprovare.');
    }
    if (Number.isSafeInteger(data.submissionId) && data.submissionId > 0) return String(data.submissionId);
    if (typeof data.error?.message === 'string') throw new Error(data.error.message.slice(0, 200));
    throw new Error('Training Olinfo ha rifiutato l’invio. Controlla account e limiti sul sito.');
  }
}

module.exports = { SubmissionClient, LoginRequired, submissionList, submitOptions, loginScripts, actionReference, origin };
