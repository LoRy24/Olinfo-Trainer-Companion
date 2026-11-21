const vscode = require('vscode');
const { readProject, readSource } = require('./project-link');
const { LoginRequired, SubmissionClient, origin } = require('./submission-client');
const { scoreColor } = require('./account');
const { taskPage } = require('./training');

const palette = { red: '#f87171', orange: '#fb923c', yellow: '#facc15', lime: '#a3e635', green: '#4ade80' };

const key = 'olinfo.training.session';
const historyPath = link => origin + '/task/' + link.task + '/submissions';
const errorMessage = error => error instanceof Error ? error.message : 'Operazione non riuscita.';

function registerSubmissions(context, onChange, openAccount) {
  const api = new SubmissionClient();
  const item = vscode.window.createStatusBarItem('olinfoTrainer.score', vscode.StatusBarAlignment.Right, 30);
  item.command = 'olinfoTrainer.submissionMenu';
  let working = false;
  let refreshId = 0;
  let nextRefresh = 0;
  let needsLogin = false;
  let activeRoot;
  const monitored = new Map();
  let current;
  let account;
  let accountVisible = false;
  let accountRequest = 0;
  let nextAccount = 0;
  let cachedSession;
  let accountBusy = false;
  let problemMeta;
  const publish = () => onChange({ problem: current, account });

  async function refreshAccount(force = false) {
    if (accountBusy || (!force && (!accountVisible || Date.now() < nextAccount))) return;
    accountBusy = true;
    const id = ++accountRequest;
    nextAccount = Date.now() + 25_000;
    try {
      const session = await context.secrets.get(key);
      const previous = session === cachedSession && account?.username ? account : undefined;
      const username = previous?.username || await api.username(session);
      const details = await api.profile(username);
      if (id !== accountRequest) return;
      cachedSession = session;
      const updateRank = !previous || previous.points !== details.points || Date.now() >= (previous.rankUntil || 0);
      account = { username, ...details, rank: updateRank ? undefined : previous.rank,
        rankUntil: updateRank ? Date.now() + 180_000 : previous.rankUntil, ranking: updateRank && details.points > 0 };
      publish();
      if (updateRank && details.points > 0) {
        const rank = await api.rank(username, details.points).catch(() => undefined);
        if (id !== accountRequest) return;
        account = { ...account, rank, ranking: false,
          rankUntil: rank === undefined ? Date.now() + 60_000 : account.rankUntil };
      }
    } catch (error) {
      if (id !== accountRequest) return;
      account = { signedOut: error instanceof LoginRequired, error: errorMessage(error) };
    } finally {
      accountBusy = false;
      if (id === accountRequest) publish();
    }
  }

  const setProblem = value => { current = value; publish(); };

  function rootFolder() {
    const editor = vscode.window.activeTextEditor?.document.uri;
    const selected = editor && vscode.workspace.getWorkspaceFolder(editor);
    const folder = selected || (vscode.workspace.workspaceFolders?.length === 1 && vscode.workspace.workspaceFolders[0]);
    return folder?.uri.scheme === 'file' ? folder : undefined;
  }

  async function project() {
    if (!vscode.workspace.isTrusted) return undefined;
    const folder = rootFolder();
    return folder && readProject(folder.uri.fsPath);
  }

  async function refresh(force = false) {
    if (!force && Date.now() < nextRefresh) return;
    nextRefresh = Date.now() + 45_000;
    const id = ++refreshId;
    let problem;
    try {
      const link = await project();
      if (id !== refreshId) return;
      if (!link) {
        item.hide();
        setProblem(undefined);
        return;
      }
      if (problemMeta?.task !== link.task || !problemMeta.title) {
        const meta = await api.page('/task/' + link.task).then(taskPage).catch(() => undefined);
        if (id !== refreshId) return;
        problemMeta = { task: link.task, title: meta?.title, mode: meta?.mode };
      }
      problem = { ...problemMeta, language: link.language, source: link.source };
      item.name = 'Punteggio Olinfo';
      item.tooltip = link.task + ' · Comandi Olinfo';
      const session = await context.secrets.get(key);
      if (id !== refreshId) return;
      if (!session) {
        needsLogin = true;
        item.text = '$(sign-in) Olinfo';
        item.color = undefined;
        item.show();
        setProblem({ ...problem, signedOut: true });
        return;
      }
      const result = await api.results(link.task, session);
      if (id !== refreshId) return;
      needsLogin = false;
      const previous = monitored.get(link.root);
      const tracked = previous && result.rows.find(row => row.id === previous.id);
      const expired = previous && Date.now() >= previous.until;
      const submission = tracked || (!previous || expired ? result.rows[0] : undefined);
      const waiting = (previous && !tracked && !expired) || submission?.pending;
      if (waiting && (!previous || Date.now() < previous.until)) nextRefresh = Date.now() + 5_000;
      if (tracked && !tracked.pending) {
        monitored.delete(link.root);
        void vscode.window.showInformationMessage('Olinfo #' + tracked.id + ' · ' + tracked.outcome
          + (tracked.score !== undefined ? ' pt' : ''));
        if (accountVisible) void refreshAccount(true);
      } else if (expired && !tracked) {
        monitored.delete(link.root);
      }
      const best = result.best === undefined ? '—' : result.best + ' pt';
      const color = result.rows.find(row => row.score !== undefined && row.score === result.best)?.color ?? scoreColor(result.best);
      item.text = (waiting ? '$(sync~spin)' : '$(circle-filled)') + ' Olinfo ' + best;
      item.color = palette[color];
      item.tooltip = link.task + ' · Miglior punteggio: ' + best
        + (submission ? '\nUltimo invio #' + submission.id + ' · ' + submission.outcome : '');
      item.show();
      const rows = result.rows.slice(0, 20).map(row => ({ ...row, color: row.color ?? scoreColor(row.score) }));
      setProblem({ ...problem, color, best: result.best, waiting: !!waiting, rows });
    } catch (error) {
      if (id !== refreshId) return;
      if (error instanceof LoginRequired) needsLogin = true;
      item.text = error instanceof LoginRequired ? '$(sign-in) Olinfo' : '$(warning) Olinfo';
      item.color = undefined;
      item.tooltip = errorMessage(error);
      item.show();
      setProblem(problem && { ...problem, signedOut: error instanceof LoginRequired, error: errorMessage(error) });
    }
  }

  async function login() {
    const username = await vscode.window.showInputBox({
      title: 'Accedi a Training Olinfo', prompt: 'Username o email', ignoreFocusOut: true,
      validateInput: value => value.trim() ? undefined : 'Inserisci username o email.',
    });
    if (!username?.trim()) return undefined;
    const password = await vscode.window.showInputBox({
      title: 'Accedi a Training Olinfo', prompt: 'Password di Training Olinfo', password: true, ignoreFocusOut: true,
    });
    if (!password) return undefined;
    const session = await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'Accesso a Training Olinfo…' },
      () => api.login(username.trim(), password));
    await context.secrets.store(key, session);
    needsLogin = false;
    monitored.clear();
    return session;
  }

  async function submit() {
    const link = await project();
    if (!link) throw new Error('Apri un progetto Olinfo attendibile con olinfo.xml.');
    if (!link.source) throw new Error('Questo problema richiede un invio di soli output dal sito.');
    let session = await context.secrets.get(key);
    if (!session || needsLogin) session = await login();
    if (!session) return;
    const confirmed = await vscode.window.showWarningMessage(
      'Inviare ' + link.source + ' a Training Olinfo per il problema ' + link.task + '?', { modal: true }, 'Invia');
    if (confirmed !== 'Invia') return;
    const uri = vscode.Uri.file(link.root);
    const sourceUri = vscode.Uri.joinPath(uri, link.source);
    const editor = vscode.workspace.textDocuments.find(doc => doc.uri.toString() === sourceUri.toString());
    if (editor?.isDirty && !await editor.save()) throw new Error('Salva il sorgente prima dell’invio.');
    const source = await readSource(link);
    const id = await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'Invio della soluzione a Olinfo…' },
      () => api.submit(link, source, session));
    monitored.set(link.root, { id, until: Date.now() + 120_000 });
    void vscode.window.showInformationMessage('Olinfo ha ricevuto l’invio #' + id + '.');
    await refresh(true);
  }

  async function run(operation) {
    if (working) return;
    working = true;
    try {
      await operation();
    } catch (error) {
      if (error instanceof LoginRequired) {
        needsLogin = true;
        void refresh(true);
      }
      void vscode.window.showErrorMessage(errorMessage(error));
    } finally {
      working = false;
    }
  }

  const commands = {
    login: () => run(async () => { if (await login()) await refresh(true); }),
    logout: () => run(async () => { await context.secrets.delete(key); monitored.clear(); await refresh(true); }),
    submit: () => run(submit),
    refreshScore: () => refresh(true),
    submissionMenu: openAccount,
  };

  const watcher = vscode.workspace.createFileSystemWatcher('**/olinfo.xml');
  const timer = setInterval(() => { void refresh(); void refreshAccount(); }, 5000);
  context.subscriptions.push(item, watcher, { dispose: () => { ++refreshId; clearInterval(timer); } },
    watcher.onDidCreate(() => void refresh(true)), watcher.onDidChange(() => void refresh(true)),
    watcher.onDidDelete(() => void refresh(true)), vscode.workspace.onDidGrantWorkspaceTrust(() => void refresh(true)),
    vscode.workspace.onDidChangeWorkspaceFolders(() => void refresh(true)),
    vscode.window.onDidChangeActiveTextEditor(() => {
      const root = rootFolder()?.uri.fsPath;
      if (root === activeRoot) return;
      activeRoot = root;
      void refresh(true);
    }),
    context.secrets.onDidChange(event => {
      if (event.key !== key) return;
      ++accountRequest;
      nextAccount = 0;
      cachedSession = undefined;
      account = undefined;
      publish();
      if (accountVisible) void refreshAccount(true);
      void refresh(true);
    }),
    ...Object.entries(commands).map(([name, run]) => vscode.commands.registerCommand('olinfoTrainer.' + name, run)));
  activeRoot = rootFolder()?.uri.fsPath;
  void refresh(true);
  return {
    setAccountVisible(visible) { accountVisible = visible; if (visible) void refreshAccount(true); },
    async open(kind, id) {
      if (kind === 'profile' && account?.username) {
        await vscode.env.openExternal(vscode.Uri.parse(origin + '/user/' + encodeURIComponent(account.username)));
        return;
      }
      const link = await project();
      if (!link) return;
      if (kind === 'history') await vscode.env.openExternal(vscode.Uri.parse(historyPath(link)));
      if (kind === 'problem') await vscode.env.openExternal(vscode.Uri.parse(origin + '/task/' + link.task));
      if (kind === 'row' && typeof id === 'string' && /^[1-9]\d*$/.test(id)) {
        await vscode.env.openExternal(vscode.Uri.parse(historyPath(link) + '/' + id));
      }
    },
  };
}

module.exports = { registerSubmissions };
