const vscode = acquireVsCodeApi();
const ui = Object.fromEntries([...document.querySelectorAll('[id]')].map(element => [element.id, element]));
let sequence = 0;
let searchId = 0;
let detailsId = 0;
let page = 1;
let query = '';
let detail;
let suggestedName = '';
let hasNext = false;
let searching = false;
let creating = false;

function status(element, text, error = false) {
  element.textContent = text;
  element.classList.toggle('error', error);
}

function updateControls() {
  ui.previous.disabled = creating || searching || page <= 1;
  ui.next.disabled = creating || searching || !hasNext;
  ui.results.disabled = searching || creating || !ui.results.options.length;
  ui.language.disabled = creating || !ui.language.options.length;
  ui.locale.disabled = creating || !ui.locale.options.length;
  ui.create.disabled = creating || !detail || !ui.language.value || !ui.locale.value;
  ui.search.querySelector('button').disabled = creating;
  for (const id of ['project-name', 'parent', 'browse']) ui[id].disabled = creating;
}

function updateGraderMessage() {
  const language = detail?.languages.find(option => option.id === ui.language.value);
  const notes = detail ? [...detail.notes] : [];
  if (detail?.mode === 'grader' && language) {
    if (!language.hasGrader) notes.unshift('Training Olinfo non pubblica il grader per questo linguaggio.');
    if (!language.hasStarter) notes.unshift('La firma da implementare è indicata nel PDF.');
  }
  ui['grader-status'].textContent = notes.join(' ');
  ui['grader-status'].hidden = !notes.length;
}

function selectTask() {
  detailsId = ++sequence;
  detail = undefined;
  ui.locale.replaceChildren();
  ui.language.replaceChildren();
  updateGraderMessage();
  updateControls();
  const id = ui.results.value;
  status(ui['task-status'], id ? 'Caricamento del problema…' : 'Seleziona un problema dal catalogo.');
  if (id) vscode.postMessage({ type: 'details', requestId: detailsId, id });
}

function search(newQuery, newPage) {
  query = newQuery;
  page = newPage;
  searching = true;
  hasNext = false;
  searchId = ++sequence;
  ui.results.replaceChildren();
  selectTask();
  status(ui['search-status'], 'Ricerca in corso…');
  vscode.postMessage({ type: 'search', requestId: searchId, query, page });
}

ui.search.addEventListener('submit', event => {
  event.preventDefault();
  search(ui.query.value, 1);
});
ui.previous.addEventListener('click', () => search(query, page - 1));
ui.next.addEventListener('click', () => search(query, page + 1));
ui.results.addEventListener('change', selectTask);
ui.language.addEventListener('change', updateGraderMessage);
ui.browse.addEventListener('click', () => vscode.postMessage({ type: 'browse', parent: ui.parent.value }));
ui.open.addEventListener('click', () => vscode.postMessage({ type: 'open' }));
ui.project.addEventListener('submit', event => {
  event.preventDefault();
  if (ui.create.disabled) return;
  const { value: name } = ui['project-name'];
  const { value: parent } = ui.parent;
  creating = true;
  ui.open.hidden = true;
  status(ui['create-status'], 'Download e creazione in corso…');
  updateControls();
  vscode.postMessage({ type: 'create', id: detail.id, language: ui.language.value, locale: ui.locale.value, parent, name });
});

window.addEventListener('message', event => {
  const message = event.data;
  switch (message.type) {
    case 'folder':
      ui.parent.value = message.path;
      break;
    case 'results':
      if (message.requestId !== searchId) return;
      searching = false;
      hasNext = message.hasNext;
      for (const task of message.tasks) ui.results.add(new Option(task.title + ' · ' + task.id, task.id));
      status(ui['search-status'], message.tasks.length ? 'Pagina ' + page : 'Nessun problema trovato');
      if (message.tasks.length === 1) {
        ui.results.selectedIndex = 0;
        selectTask();
      }
      break;
    case 'details':
      if (message.requestId !== detailsId) return;
      detail = message;
      for (const locale of message.pdf) ui.locale.add(new Option(locale.label, locale.id));
      for (const language of message.languages) ui.language.add(new Option(language.label, language.id));
      status(ui['task-status'], !message.pdf.length ? 'Il PDF non è disponibile.'
        : !message.languages.length ? 'Nessun sorgente o grader pubblico disponibile.' : message.title + ' · ' + message.id);
      if (!ui['project-name'].value || ui['project-name'].value === suggestedName) {
        ui['project-name'].value = message.id;
        suggestedName = message.id;
      }
      updateGraderMessage();
      break;
    case 'created':
      creating = false;
      ui.open.hidden = false;
      status(ui['create-status'], 'Progetto creato in ' + message.path);
      break;
    case 'error':
      if (message.action === 'search') {
        if (message.requestId !== searchId) return;
        searching = false;
        status(ui['search-status'], message.message, true);
      } else if (message.action === 'details') {
        if (message.requestId !== detailsId) return;
        status(ui['task-status'], message.message, true);
      } else {
        creating = false;
        status(ui['create-status'], message.message, true);
      }
      break;
  }
  updateControls();
});

vscode.postMessage({ type: 'ready' });
search('', 1);
