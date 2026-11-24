const { load } = require('cheerio/slim');
const { shortTaskId } = require('./project-link');
const { LoginRequired, flight, loginScripts, pathFor, terryId, origin } = require('./submission-client');

const actions = /createServerReference[^\w"]{0,12}"([a-f0-9]{40,64})"[^;]{0,180}?"(requestInput|changeInput|uploadAndSubmit)"/g;
const errors = {
  CGREVm: 'L’input è scaduto. Avvia un nuovo invio per richiederne un altro.',
  'RDQh2-': 'Questo input è già stato usato. Controlla gli invii e riprova.',
  Zw8V2_: 'Training Olinfo non autorizza l’invio con questo account. Controlla la registrazione sul sito.',
};
const unsure = 'Esito dell’invio incerto. Controlla lo storico prima di riprovare.';

function formInput(html, task) {
  const $ = load(html);
  $('script').remove();
  if (/Accedi per inviare soluzioni/i.test($.root().text())) throw new LoginRequired();
  for (const row of flight(html).split('\n')) {
    const data = row.slice(row.indexOf(':') + 1);
    if (!data.startsWith('["$"') || !data.includes('"submissionTimeout"')) continue;
    const props = JSON.parse(data)[3];
    if (props?.task?.name !== shortTaskId(task) || !Object.hasOwn(props, 'input')) continue;
    if (props.input == null || props.input === '$undefined') return undefined;
    const { id, path, date } = props.input;
    const file = typeof path === 'string' && path.replace(/^\/+/, '');
    const created = typeof date === 'string' && Date.parse(date.replace(/^\$D/, ''));
    const seconds = props.task.submissionTimeout;
    if (!terryId.test(id) || !file || !/^input\/(?:[\w.-]+\/)*[\w.-]+\.txt$/i.test(file)
        || file.split('/').some(part => part === '.' || part === '..')
        || !Number.isFinite(created) || (seconds !== null && !Number.isFinite(seconds))) break;
    return { id, path: '/files/' + file, expired: seconds > 0 && Date.now() >= created + seconds * 1000 };
  }
  throw new Error('Impossibile leggere il modulo Territoriali aggiornato di Training Olinfo.');
}

function actionValue(text) {
  // The action result is a Flight promise referenced by the root row's "a" field.
  const rows = new Map(text.split('\n').map(line => [
    line.slice(0, line.indexOf(':')), line.slice(line.indexOf(':') + 1),
  ]));
  try {
    const value = JSON.parse(rows.get('0')).a;
    const ref = typeof value === 'string' && /^\$@([a-f0-9]+)$/.exec(value);
    return ref ? JSON.parse(rows.get(ref[1])) : value;
  } catch {
    return undefined;
  }
}

function report(html) {
  const $ = load(html);
  const scoreCell = $('li > span').filter((_, node) => $(node).text().trim() === 'Esito:').first().parent();
  const score = /^(\d{1,9})\s*\/\s*(\d{1,9})$/.exec(scoreCell.children('span').last().text().trim());
  const cases = [];
  for (const cell of $('div.font-mono')) {
    const match = /^Case #(\d+)$/.exec($(cell).text().trim());
    if (!match) continue;
    const outcome = $(cell).next().text().trim();
    if (!['Corretto', 'Errato', 'Non inviato'].includes(outcome)) {
      throw new Error('Esito di un caso Territoriali in un formato imprevisto.');
    }
    cases.push({ number: Number(match[1]), outcome, detail: $(cell).next().next().text().trim() });
  }
  if (!score) throw new Error('Dettagli della valutazione Territoriali non disponibili.');
  const notes = scoreCell.parent().find('div.whitespace-pre-line').children().map((_, node) => $(node).text().trim()).get();
  return { score: Number(score[1]), maxScore: Number(score[2]), cases, notes };
}

class TerryClient {
  constructor(api, read) {
    this.api = api;
    this.read = read;
    this.references = new Map();
  }

  async reference(html, name) {
    for (const script of loginScripts(html).reverse()) {
      let found = this.references.get(script);
      if (!found) {
        try {
          const chunk = await this.api.page(script);
          found = Object.fromEntries([...chunk.matchAll(actions)].map(([, id, action]) => [action, id]));
          if (this.references.size >= 64) this.references.clear();
          this.references.set(script, found);
        } catch { continue; }
      }
      if (found[name]) return found[name];
    }
    throw new Error('Il modulo Territoriali di Training Olinfo è cambiato.');
  }

  async action(formPath, html, name, args, session, files) {
    const action = await this.reference(html, name);
    const headers = { 'Next-Action': action, Accept: 'text/x-component', Referer: origin + formPath };
    let body = JSON.stringify(args);
    if (files) {
      const form = new FormData();
      form.append('_1_file', new Blob([files.source], { type: 'application/octet-stream' }), files.name);
      form.append('_2_file', new Blob([files.output], { type: 'text/plain' }), 'output.txt');
      form.append('0', JSON.stringify([...args, '$K1', '$K2']));
      const upload = new Request(origin + formPath, { method: 'POST', body: form });
      headers['Content-Type'] = upload.headers.get('content-type');
      body = new Uint8Array(await upload.arrayBuffer());
      if (body.length > 1024 * 1024) {
        throw new Error('Sorgente e output insieme superano il limite di 1 MiB dell’invio Territoriali.');
      }
    } else {
      headers['Content-Type'] = 'text/plain;charset=UTF-8';
    }
    let result;
    try {
      result = await this.api.exchange(formPath, { session, headers, body });
    } catch (error) {
      if (!files || error instanceof LoginRequired) throw error;
      throw new Error(unsure);
    }
    const { response, text } = result;
    const redirect = (response.headers.get('x-action-redirect') || response.headers.get('location') || '').split(';', 1)[0];
    if (response.status === 401 || /^\/login(?:[?/]|$)/.test(redirect)) throw new LoginRequired();
    if (response.headers.get('x-nextjs-action-not-found')) throw new Error('Training Olinfo è stato aggiornato. Riprova.');
    const value = actionValue(text);
    if (value?.id === 'RAHXa3') throw new LoginRequired();
    if (Object.hasOwn(errors, value?.id)) throw new Error(errors[value.id]);
    if (typeof value?.message === 'string') throw new Error(value.message.slice(0, 500));
    if (typeof value === 'string' && !value.startsWith('$')) throw new Error(value.slice(0, 500));
    if (files) {
      const prefix = formPath.replace(/submit$/, 'submissions/');
      const id = redirect.startsWith(prefix) && redirect.slice(prefix.length);
      if (id && terryId.test(id)) return id;
      if (value?.id) throw new Error('Training Olinfo ha segnalato un errore durante l’invio. Controlla lo storico.');
      throw new Error(unsure);
    }
    if (!response.ok || (value?.id && value.id !== 'oYs_5j')) {
      throw new Error('Training Olinfo non è riuscito a preparare l’input.');
    }
  }

  async input(task, session) {
    if (!session) throw new LoginRequired();
    const location = pathFor(task, 'submit');
    let html = await this.api.page(location, session);
    let input = formInput(html, task);
    if (input?.expired) {
      await this.action(location, html, 'changeInput', [input.id], session);
      html = await this.api.page(location, session);
      input = formInput(html, task);
    }
    if (!input) {
      await this.action(location, html, 'requestInput', [shortTaskId(task)], session);
      html = await this.api.page(location, session);
      input = formInput(html, task);
    }
    if (!input || input.expired) throw new Error('Training Olinfo non ha restituito un input utilizzabile.');
    const bytes = await this.read(input.path, 64 * 1024 * 1024);
    if (!bytes.length) throw new Error('Training Olinfo ha restituito un input vuoto.');
    return { ...input, html, bytes };
  }

  async submit(link, input, source, output, session) {
    if (!session) throw new LoginRequired();
    return this.action(pathFor(link.task, 'submit'), input.html, 'uploadAndSubmit', [shortTaskId(link.task), input.id],
      session, { source, output, name: link.source });
  }

  async result(task, id, session) {
    if (!session) throw new LoginRequired();
    if (!terryId.test(id)) throw new Error('Identificativo dell’invio non valido.');
    return report(await this.api.page(pathFor(task, 'submissions/' + id), session));
  }
}

module.exports = { TerryClient, formInput, report };
