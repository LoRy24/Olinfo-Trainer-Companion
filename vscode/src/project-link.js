const fs = require('node:fs/promises');
const path = require('node:path');
const { load } = require('cheerio/slim');

const taskId = /^(?:terry\/)?[A-Za-z0-9][A-Za-z0-9_-]{0,119}$/;
const isTerry = task => task.startsWith('terry/');
const shortTaskId = task => isTerry(task) ? task.slice(6) : task;
const extensions = { cpp: ['cpp', 'cc', 'cxx'], c: ['c'], py: ['py'], java: ['java'], pas: ['pas', 'pp'] };
const maxSource = 100_000;

function validate({ task, language, source = '' }) {
  if (!taskId.test(task ?? '') || !(Object.hasOwn(extensions, language) || (language === 'output' && !isTerry(task)))) {
    throw new Error('Problema o linguaggio non valido in olinfo.xml.');
  }
  if (language === 'output' && !source) return { task, language, source };
  const extension = path.extname(source).slice(1).toLowerCase();
  if (!/^[a-z0-9_][a-z0-9_.-]*$/i.test(source) || !extensions[language]?.includes(extension)
      || /^(?:grader|stub)(?:[._-]|$)/i.test(source)) {
    throw new Error('Indica il file della soluzione in olinfo.xml.');
  }
  return { task, language, source };
}

function projectXml(link) {
  const { task, language, source } = validate(link);
  const attribute = source ? ' source="' + source + '"' : '';
  return '<?xml version="1.0" encoding="UTF-8"?>\n<olinfo version="1" task="' + task
    + '" language="' + language + '"' + attribute + '/>\n';
}

async function readProject(root) {
  const file = path.join(root, 'olinfo.xml');
  try {
    const info = await fs.lstat(file);
    if (!info.isFile() || info.size > 8192) throw new Error('olinfo.xml non valido.');
    const xml = load(await fs.readFile(file, 'utf8'), { xmlMode: true }, false);
    const entry = xml.root().children();
    if (entry.length !== 1 || !entry.is('olinfo[version="1"]')) throw new Error('olinfo.xml non valido.');
    return { root, ...validate(entry.attr()) };
  } catch (error) {
    if (error.code === 'ENOENT') return undefined;
    throw error;
  }
}

async function readSource(link) {
  if (!link.source) throw new Error('Questo problema richiede un invio di soli output dal sito.');
  const root = await fs.realpath(link.root);
  const source = path.join(root, link.source);
  if (await fs.realpath(source) !== source) throw new Error('Scegli un sorgente effettivo nella cartella del progetto.');
  const handle = await fs.open(source, 'r');
  try {
    const info = await handle.stat();
    if (!info.isFile() || !info.size || info.size > maxSource) throw new Error('Il sorgente deve contenere da 1 a 100.000 byte.');
    const buffer = Buffer.alloc(maxSource + 1);
    let size = 0;
    while (size < buffer.length) {
      const { bytesRead } = await handle.read(buffer, size, buffer.length - size);
      if (!bytesRead) break;
      size += bytesRead;
    }
    if (!size || size > maxSource) throw new Error('Dimensione del sorgente non valida.');
    return buffer.subarray(0, size);
  } finally {
    await handle.close();
  }
}

module.exports = { projectXml, readProject, readSource, taskId, isTerry, shortTaskId, extensions };
