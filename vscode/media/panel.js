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

function selectTab(tab) {
  const isNew = tab === 'new';
  ui['tab-new'].setAttribute('aria-pressed', String(isNew));
  ui['tab-current'].setAttribute('aria-pressed', String(!isNew));
  ui['new-pane'].hidden = !isNew;
  ui['current-pane'].hidden = isNew;
}

function action(name) { vscode.postMessage({ type: 'submissionAction', action: name }); }
function openTraining(kind, id) { vscode.postMessage({ type: 'openTraining', kind, id }); }

const format = value => new Intl.NumberFormat('it-IT').format(value);
const safeColor = color => ['red', 'orange', 'yellow', 'lime', 'green'].includes(color) ? color : 'neutral';

function renderDashboard({ account, problem }) {
  ui['current-title'].textContent = problem?.title || problem?.task || 'Questo problema';
  ui['current-info'].textContent = problem
    ? [problem.task, problem.language?.toUpperCase(), problem.source, problem.mode].filter(Boolean).join(' · ')
    : 'Apri una cartella con olinfo.xml.';
  ui['current-score'].hidden = !problem?.rows;
  ui['current-score'].textContent = 'Migliore · ' + (problem?.best ?? '—');
  ui['current-score'].dataset.color = safeColor(problem?.color);
  ui['current-open'].disabled = !problem?.task;
  ui['current-submit'].disabled = !problem?.source;
  ui['current-login'].hidden = !problem?.signedOut;
  ui['current-history'].hidden = !problem?.task;
  status(ui['current-message'], problem?.error || (problem?.signedOut ? 'Accedi per vedere i tuoi invii.'
    : problem?.waiting ? 'Valutazione in corso…' : problem?.rows?.length === 0 ? 'Nessun invio.' : ''));
  ui['current-results'].replaceChildren();
  for (const row of problem?.rows || []) {
    const entry = document.createElement('li');
    const button = document.createElement('button');
    button.className = 'secondary';
    const number = document.createElement('span');
    number.textContent = '#' + row.id;
    const outcome = document.createElement('span');
    outcome.className = 'score';
    outcome.dataset.color = safeColor(row.color);
    outcome.textContent = row.score === undefined ? row.outcome : row.score + ' pt';
    button.append(number, outcome);
    button.addEventListener('click', () => openTraining('row', row.id));
    entry.append(button);
    ui['current-results'].append(entry);
  }

  const signedIn = !!account?.username;
  ui['account-details'].hidden = !signedIn;
  ui['account-profile'].hidden = !signedIn;
  ui['account-logout'].hidden = !signedIn;
  ui['account-login'].hidden = !account?.signedOut;
  ui['account-message'].textContent = account?.error || (!account ? 'Caricamento account…' : '');
  if (signedIn) {
    ui['account-user'].textContent = '@' + account.username;
    ui['avatar-initials'].textContent = account.username.slice(0, 2).toUpperCase();
    ui['account-points'].textContent = format(account.points);
    ui['account-solved'].textContent = format(account.solved);
    ui['account-rank'].textContent = account.ranking ? '…' : account.rank ? '#' + format(account.rank) : '—';
    const photo = ui['avatar-photo'];
    if (photo.getAttribute('src') !== (account.avatar ?? null)) {
      photo.hidden = true;
      ui['avatar-initials'].hidden = false;
      if (account.avatar) photo.setAttribute('src', account.avatar);
      else photo.removeAttribute('src');
    }
  }
}

ui['avatar-photo'].addEventListener('load', () => { ui['avatar-photo'].hidden = false; ui['avatar-initials'].hidden = true; });
ui['avatar-photo'].addEventListener('error', () => { ui['avatar-photo'].hidden = true; ui['avatar-initials'].hidden = false; });
ui['tab-new'].addEventListener('click', () => selectTab('new'));
ui['tab-current'].addEventListener('click', () => selectTab('current'));
ui['account-close'].addEventListener('click', () => ui['account-dialog'].close());
ui['account-dialog'].addEventListener('close', () => vscode.postMessage({ type: 'account', visible: false }));
ui['account-login'].addEventListener('click', () => action('login'));
ui['account-logout'].addEventListener('click', () => action('logout'));
ui['account-profile'].addEventListener('click', () => openTraining('profile'));
ui['current-login'].addEventListener('click', () => action('login'));
ui['current-submit'].addEventListener('click', () => action('submit'));
ui['current-refresh'].addEventListener('click', () => action('refreshScore'));
ui['current-open'].addEventListener('click', () => openTraining('problem'));
ui['current-history'].addEventListener('click', () => openTraining('history'));

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
    case 'dashboard':
      renderDashboard(message);
      break;
    case 'tab':
      selectTab(message.tab);
      break;
    case 'showAccount':
      if (!ui['account-dialog'].open) ui['account-dialog'].showModal();
      vscode.postMessage({ type: 'account', visible: true });
      break;
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
