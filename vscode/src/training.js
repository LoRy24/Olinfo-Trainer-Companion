const { load } = require('cheerio/slim');
const { unzipSync } = require('fflate');
const fs = require('node:fs');
const path = require('node:path');
const { taskId, isTerry, shortTaskId } = require('./project-link');

const source = typeof CATALOG_PROPERTIES === 'undefined'
  ? fs.readFileSync(path.join(__dirname, '../../shared/catalog.properties'), 'utf8')
  : CATALOG_PROPERTIES;
const config = Object.fromEntries(source.trim().split(/\r?\n/).map(line => line.split('=')));
const origin = config.origin;
const locales = config['pdf.locales'].split(',');
const languages = config['code.languages'].split(',');
const languageLabel = id => config['code.' + id] || id;
const filePattern = /^\/files\/[a-f0-9]{40}\/[^/?#]+$/i;
const statementPattern = /\/files\/[a-f0-9]{40}\/testo\.pdf(?=["'\\#\s]|$)/gi;
const archiveLimit = 24 * 1024 * 1024;
const assetLimit = 4 * 1024 * 1024;
const pdfLimit = 32 * 1024 * 1024;

function searchPage(html, page, regional = false) {
  const $ = load(html);
  const tasks = new Map();
  let hasNext = false;
  for (const link of $('a[href]')) {
    const href = $(link).attr('href');
    const id = href.startsWith('/task/') && href.slice(6);
    const title = $(link).text().trim();
    if (id && taskId.test(id) && isTerry(id) === regional && title) tasks.set(id, { id, title });
    if (href.split('?')[0] === '/tasks/' + (regional ? 'terry/' : '') + (page + 1)) hasNext = true;
  }
  return { tasks: [...tasks.values()], hasNext };
}

function taskPage(html, id) {
  const $ = load(html);
  const header = $('header').has('h1').first();
  const title = header.find('h1').text().trim();
  if (id && isTerry(id)) {
    if (!title) throw new Error('La pagina del problema Territoriali ha un formato imprevisto.');
    const base = '/files/statements/secret/' + shortTaskId(id) + '/';
    const attachments = $('main a[download][href]').map((_, link) => {
      const url = $(link).attr('href');
      const name = url.slice(base.length);
      return url.startsWith(base) && /^[a-z0-9_][a-z0-9_.-]*$/i.test(name) ? { name, url } : null;
    }).get();
    return { title, mode: 'terry', attachments };
  }
  const ioLabel = header.find('span').filter((_, item) => $(item).text().trim() === 'Input/output:').first();
  const mode = ioLabel.parent().text().replace(/^Input\/output:\s*/, '').trim();
  if (!title || !['grader', 'output-only', 'file', 'stdin / stdout'].includes(mode)) {
    throw new Error('La pagina del problema ha un formato imprevisto.');
  }
  const flags = new Set(header.find('svg title').map((_, item) => $(item).text().trim()).get());
  const translations = locales.filter(id => flags.has(config['pdf.' + id]));
  const links = new Map();
  for (const link of $('a[download][href]')) {
    const url = $(link).attr('href');
    if (!filePattern.test(url)) continue;
    const name = $(link).text().trim();
    if (name && !/[\\/\x00-\x1f]/.test(name)) links.set(name, { name, url });
  }
  return { title, mode, translations, attachments: [...links.values()] };
}

function statementPath(html) {
  const $ = load(html);
  const object = $('main object[data], main iframe[src]').first();
  const embedded = (object.attr('data') || object.attr('src') || '').split('#')[0];
  if (embedded.match(/^\/files\/[a-f0-9]{40}\/testo\.pdf$/i)) return embedded;
  // The public page may render the PDF client-side. Its URL is in the server-rendered data.
  const urls = new Set($('script').map((_, item) => $(item).text()).get()
    .flatMap(script => script.match(statementPattern) || []));
  if (urls.size !== 1) throw new Error('Il PDF del problema non è disponibile.');
  return [...urls][0];
}

function codeLanguage(name) {
  const extension = path.extname(name).toLowerCase().slice(1);
  return ({ cc: 'cpp', cxx: 'cpp', pp: 'pas', py2: 'py', py3: 'py' })[extension] || extension;
}

function isGrader(name) {
  return /^(?:grader|stub)(?:[._-][a-z0-9]+)?\.[a-z0-9]+$/i.test(name);
}

function isHeader(name) {
  return /^[a-z0-9_][a-z0-9_.-]*\.(?:h|hh|hpp|hxx)$/i.test(name);
}

function sampleInfo(name) {
  const match = /^(.*?)(?:^|[._-])(input|output)(?:[._-]?(\d+))?\.txt$/i.exec(name);
  if (match) return { key: match[1].toLowerCase() + ':' + (match[3] || '0'), kind: match[2].toLowerCase() };
  const short = /^(.*?)(?:[._-]?(\d+))?\.(in|out)$/i.exec(name);
  return short && { key: short[1].toLowerCase() + ':' + (short[2] || '0'), kind: short[3] === 'in' ? 'input' : 'output' };
}

function unpack(bytes, archiveName) {
  let expanded = 0;
  const files = unzipSync(bytes, {
    filter(entry) {
      const name = path.posix.basename(entry.name);
      const wanted = languages.includes(codeLanguage(name)) || isHeader(name) || sampleInfo(name);
      if (!wanted || !/^[a-z0-9_][a-z0-9_.-]*$/i.test(name) || /(?:^|\/)__MACOSX\//.test(entry.name)) return false;
      // Read only recognized files into memory, and never use an archive entry as a destination path.
      if (entry.originalSize > assetLimit) return false;
      expanded += entry.originalSize;
      if (expanded > 12 * 1024 * 1024) throw new Error('Archivio troppo grande.');
      return true;
    },
  });
  return Object.entries(files).map(([entry, data]) => ({
    name: path.posix.basename(entry),
    group: archiveName + '/' + path.posix.dirname(entry).replace(/(?:^|\/)(?:input|output)s?$/i, '').replace(/^\.$/, ''),
    data: Buffer.from(data),
  }));
}

function starterFor(files, id, language) {
  const shortId = id.replace(/^(?:(?:oii|ois|ioi|weoi|ceoi|eioi|egoi|itoi|sortilegio)_)+/i, '');
  const aliases = [shortId, id, 'template', 'starter', 'skeleton', 'file', 'main'];
  const candidates = files.filter(file => codeLanguage(file.name) === language && !isGrader(file.name));
  for (const alias of aliases) {
    const starter = candidates.find(file => path.parse(file.name).name.toLowerCase() === alias.toLowerCase());
    if (starter) return starter;
  }
  return undefined;
}

function samplePair(files) {
  const pairs = new Map();
  const ordered = [...files].sort((a, b) => a.name.localeCompare(b.name, 'en', { numeric: true }));
  for (const file of ordered) {
    const info = sampleInfo(file.name);
    if (!info) continue;
    const key = (file.group || '') + '|' + info.key;
    if (!pairs.has(key)) pairs.set(key, {});
    pairs.get(key)[info.kind] ||= file;
  }
  return [...pairs.values()].find(pair => pair.input && pair.output) || {};
}

class TrainingClient {
  constructor(request = fetch) {
    this.request = request;
  }

  async read(url, limit, locale = 'it-IT') {
    const target = new URL(url, origin);
    if (target.origin !== origin) throw new Error('Indirizzo di download non valido.');
    const response = await this.request(target, {
      redirect: 'error',
      headers: { 'Accept-Language': locale, Cookie: 'lang=' + locale, 'Cache-Control': 'no-cache' },
      signal: AbortSignal.timeout(25000),
    });
    if (!response.ok || !response.body) {
      await response.body?.cancel();
      throw new Error('Training Olinfo ha risposto con HTTP ' + response.status + '.');
    }

    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    try {
      if (Number(response.headers.get('content-length')) > limit) throw new Error('File troppo grande.');
      for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
        size += chunk.value.length;
        if (size > limit) throw new Error('File troppo grande.');
        chunks.push(chunk.value);
      }
      return Buffer.concat(chunks, size);
    } finally {
      await reader.cancel().catch(() => {});
    }
  }

  async search(query, page = 1, regional = false) {
    const text = query.trim();
    const search = new URLSearchParams({ search: regional ? shortTaskId(text) : text });
    const html = await this.read('/tasks/' + (regional ? 'terry/' : '') + page + '?' + search, assetLimit);
    return searchPage(html.toString('utf8'), page, regional);
  }

  async details(id) {
    if (!taskId.test(id)) throw new Error('Identificativo del problema non valido.');
    const html = (await this.read('/task/' + id, assetLimit)).toString('utf8');
    const result = taskPage(html, id);
    if (isTerry(id)) {
      const base = '/files/statements/secret/' + shortTaskId(id) + '/';
      const markdown = (await this.read(base + 'statement.md', assetLimit)).toString('utf8');
      const files = [];
      for (const kind of ['input', 'output']) {
        const example = new RegExp('^\\*\\*' + kind + ':\\*\\*[^\\r\\n]*\\r?\\n'
          + '(?:[ \\t]*\\r?\\n)*[ \\t]*`{3}[^\\r\\n]*\\r?\\n([\\s\\S]*?)^[ \\t]*`{3}', 'im').exec(markdown);
        if (example) files.push({ name: kind + '.txt', data: Buffer.from(example[1]) });
      }
      const statement = Buffer.from(markdown.replace(/(\]\()([\w][\w.-]*(?:\/[\w][\w.-]*)*)(?=[\s)])/g, '$1' + origin + base + '$2'));
      const options = languages.map(language => ({ id: language, label: languageLabel(language),
        starter: result.attachments.find(file => codeLanguage(file.name) === language) }));
      return { id, ...result, files, options, notes: [], statement, pdf: [{ id: 'original', label: 'Originale (Markdown)' }] };
    }
    const files = [...result.attachments];
    const notes = [];
    const archives = result.attachments.filter(file => /\.zip$/i.test(file.name));
    for (const archive of archives.slice(0, 6)) {
      try {
        files.push(...unpack(await this.read(archive.url, archiveLimit), archive.name));
      } catch {
        notes.push('Archivio non disponibile o oltre il limite di importazione: ' + archive.name);
      }
    }
    if (archives.length > 6) notes.push('Sono stati consultati i primi sei archivi del problema.');

    const options = [];
    for (const idLanguage of languages) {
      const grader = files.find(file => isGrader(file.name) && codeLanguage(file.name) === idLanguage);
      const starter = starterFor(files, id, idLanguage);
      if (result.mode !== 'output-only' && (result.mode !== 'grader' || grader || starter)) {
        options.push({ id: idLanguage, label: languageLabel(idLanguage), grader, starter });
      }
    }
    if (result.mode === 'output-only') options.push({ id: 'output', label: 'Solo output' });
    let defaultPdf;
    try { defaultPdf = statementPath(html); } catch { /* Try the selected translation on creation. */ }
    const pdf = result.translations.map(idLocale => ({ id: idLocale, label: config['pdf.' + idLocale] }));
    if (!pdf.length && defaultPdf) pdf.push({ id: 'original', label: 'Originale' });
    return { id, ...result, files, notes, options, pdf, defaultPdf };
  }

  async pdf(details, locale) {
    if (!details.pdf.some(item => item.id === locale)) throw new Error('Traduzione non disponibile.');
    let url = details.defaultPdf;
    if (!url || !['it-IT', 'original'].includes(locale)) {
      const html = await this.read('/task/' + details.id, assetLimit, locale);
      const page = html.toString('utf8');
      if (locale !== 'original' && load(page)('html').attr('lang') !== locale) {
        throw new Error('Training Olinfo non ha restituito la lingua richiesta.');
      }
      url = statementPath(page);
    }
    const bytes = await this.read(url, pdfLimit);
    if (bytes.subarray(0, 5).toString() !== '%PDF-') throw new Error('Il testo scaricato non è un PDF.');
    return bytes;
  }

  async asset(file) {
    if (!file) return Buffer.alloc(0);
    return file.data || this.read(file.url, assetLimit);
  }
}

module.exports = { TrainingClient, searchPage, taskPage, languageLabel, statementPath, isHeader, samplePair };
