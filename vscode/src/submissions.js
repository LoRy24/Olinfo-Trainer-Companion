const vscode = require('vscode');
const { readProject, readSource } = require('./project-link');
const { LoginRequired, SubmissionClient, origin } = require('./submission-client');

const key = 'olinfo.training.session';
const historyPath = link => origin + '/task/' + link.task + '/submissions';
const errorMessage = error => error instanceof Error ? error.message : 'Operazione non riuscita.';

function registerSubmissions(context) {
  const api = new SubmissionClient();
  const item = vscode.window.createStatusBarItem('olinfoTrainer.score', vscode.StatusBarAlignment.Right, 30);
  item.command = 'olinfoTrainer.submissionMenu';
  let working = false;
  let refreshId = 0;
  let nextRefresh = 0;
  let latest;
  let needsLogin = false;
  let activeRoot;
  const monitored = new Map();

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
    try {
      const link = await project();
      if (id !== refreshId) return;
      if (!link) {
        item.hide();
        latest = undefined;
        return;
      }
      item.name = 'Punteggio Olinfo';
      item.tooltip = link.task + ' · Comandi Olinfo';
      const session = await context.secrets.get(key);
      if (id !== refreshId) return;
      if (!session) {
        needsLogin = true;
        latest = undefined;
        item.text = '$(sign-in) Olinfo';
        item.show();
        return;
      }
      const result = await api.results(link.task, session);
      if (id !== refreshId) return;
      needsLogin = false;
      const previous = monitored.get(link.root);
      const tracked = previous && result.rows.find(row => row.id === previous.id);
      const expired = previous && Date.now() >= previous.until;
      const submission = tracked || (!previous || expired ? result.rows[0] : undefined);
      latest = { root: link.root, submission };
      const waiting = (previous && !tracked && !expired) || submission?.pending;
      if (waiting && (!previous || Date.now() < previous.until)) nextRefresh = Date.now() + 5_000;
      if (tracked && !tracked.pending) {
        monitored.delete(link.root);
        void vscode.window.showInformationMessage('Olinfo #' + tracked.id + ' · ' + tracked.outcome
          + (tracked.score !== undefined ? ' pt' : ''));
      } else if (expired && !tracked) {
        monitored.delete(link.root);
      }
      const best = result.best === undefined ? '—' : result.best + ' pt';
      item.text = (waiting ? '$(sync~spin)' : '$(trophy)') + ' Olinfo ' + best;
      item.tooltip = link.task + ' · Miglior punteggio: ' + best
        + (submission ? '\nUltimo invio #' + submission.id + ' · ' + submission.outcome : '');
      item.show();
    } catch (error) {
      if (id !== refreshId) return;
      latest = undefined;
      if (error instanceof LoginRequired) needsLogin = true;
      item.text = error instanceof LoginRequired ? '$(sign-in) Olinfo' : '$(warning) Olinfo';
      item.tooltip = errorMessage(error);
      item.show();
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
    submissionMenu: async () => {
      try {
        const link = await project();
        if (!link) return;
        const session = await context.secrets.get(key);
        const actions = [
          ...(link.source ? [{ label: 'Invia soluzione', command: 'submit' }] : []),
          { label: 'Aggiorna punteggio', command: 'refreshScore' },
          ...(!session || needsLogin ? [{ label: 'Accedi a Training Olinfo', command: 'login' }] : []),
          ...(session ? [{ label: 'Esci dal plugin', command: 'logout' }] : []),
          { label: 'Apri gli invii sul sito', url: historyPath(link) },
        ];
        if (latest?.root === link.root && latest.submission) {
          actions.push({ label: 'Apri l’invio #' + latest.submission.id, url: historyPath(link) + '/' + latest.submission.id });
        }
        const action = await vscode.window.showQuickPick(actions, { title: 'Olinfo · ' + link.task });
        if (action?.command) await commands[action.command]();
        if (action?.url) await vscode.env.openExternal(vscode.Uri.parse(action.url));
      } catch (error) {
        void vscode.window.showErrorMessage(errorMessage(error));
      }
    },
  };

  const watcher = vscode.workspace.createFileSystemWatcher('**/olinfo.xml');
  const timer = setInterval(() => void refresh(), 5000);
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
    context.secrets.onDidChange(event => { if (event.key === key) void refresh(true); }),
    ...Object.entries(commands).map(([name, run]) => vscode.commands.registerCommand('olinfoTrainer.' + name, run)));
  activeRoot = rootFolder()?.uri.fsPath;
  void refresh(true);
}

module.exports = { registerSubmissions };
