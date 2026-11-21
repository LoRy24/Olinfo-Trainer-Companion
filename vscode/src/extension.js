const vscode = require('vscode');
const { readFileSync } = require('node:fs');
const { TrainingClient } = require('./training');
const { createProject } = require('./project');
const { registerSubmissions } = require('./submissions');

function activate(context) {
  let currentPanel;
  let dashboard;
  let requestedTab;
  let accountRequested = false;
  const show = async () => {
    if (currentPanel) currentPanel.show(false);
    else await vscode.commands.executeCommand('olinfoTrainer.workspace.focus');
  };
  const submissions = registerSubmissions(context, value => {
    dashboard = value;
    void currentPanel?.webview.postMessage({ type: 'dashboard', ...value });
  }, async () => {
    accountRequested = true;
    await show();
    void currentPanel?.webview.postMessage({ type: 'showAccount' });
  });
  const client = new TrainingClient();
  const media = vscode.Uri.joinPath(context.extensionUri, 'media');

  context.subscriptions.push(vscode.commands.registerCommand('olinfoTrainer.newProblem', async () => {
    requestedTab = 'new';
    await show();
    void currentPanel?.webview.postMessage({ type: 'tab', tab: requestedTab });
  }));
  const provider = { resolveWebviewView(panel) {
    currentPanel = panel;
    panel.webview.options = { enableScripts: true, localResourceRoots: [media] };
    panel.onDidDispose(() => { currentPanel = undefined; submissions.setAccountVisible(false); });
    panel.onDidChangeVisibility(() => {
      if (!panel.visible) submissions.setAccountVisible(false);
      else if (accountRequested) void panel.webview.postMessage({ type: 'showAccount' });
    });

    let details;
    let detailsRequest;
    let generated;
    let creating = false;
    const send = (type, data = {}) => panel.webview.postMessage({ type, ...data });
    panel.webview.onDidReceiveMessage(async message => {
      const { type, requestId } = message;
      try {
        switch (type) {
          case 'ready': {
            const folder = vscode.workspace.workspaceFolders?.find(item => item.uri.scheme === 'file');
            if (folder) await send('folder', { path: folder.uri.fsPath });
            if (dashboard) await send('dashboard', dashboard);
            if (requestedTab) await send('tab', { tab: requestedTab });
            if (accountRequested) await send('showAccount');
            break;
          }
          case 'account':
            accountRequested = message.visible === true;
            submissions.setAccountVisible(accountRequested);
            break;
          case 'submissionAction':
            if (['login', 'logout', 'submit', 'refreshScore'].includes(message.action)) {
              await vscode.commands.executeCommand('olinfoTrainer.' + message.action);
            }
            break;
          case 'openTraining':
            await submissions.open(message.kind, message.id);
            break;
          case 'search': {
            if (typeof message.query !== 'string' || message.query.length > 200
                || !Number.isSafeInteger(message.page) || message.page < 1) return;
            await send('results', { requestId, ...await client.search(message.query, message.page) });
            break;
          }
          case 'details': {
            details = undefined;
            detailsRequest = requestId;
            if (typeof message.id !== 'string') return;
            const result = await client.details(message.id);
            if (detailsRequest !== requestId) return;
            details = result;
            const { id, title, mode, pdf, options, notes } = details;
            await send('details', {
              requestId, id, title, mode, pdf, notes,
              languages: options.map(option => ({
                id: option.id, label: option.label, hasGrader: Boolean(option.grader), hasStarter: Boolean(option.starter),
              })),
            });
            break;
          }
          case 'browse': {
            const folders = await vscode.window.showOpenDialog({
              canSelectFolders: true, canSelectFiles: false, canSelectMany: false, openLabel: 'Scegli cartella',
              defaultUri: typeof message.parent === 'string' && message.parent
                ? vscode.Uri.file(message.parent) : undefined,
            });
            if (folders?.length) await send('folder', { path: folders[0].fsPath });
            break;
          }
          case 'create': {
            if (creating) return;
            if (!details || message.id !== details.id) throw new Error('Seleziona un problema.');
            creating = true;
            try {
              generated = await createProject(client, details, message.language, message.locale, message.parent, message.name);
              await send('created', { path: generated.target });
            } finally {
              creating = false;
            }
            break;
          }
          case 'open': {
            if (generated) {
              await vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.file(generated.target), { forceNewWindow: true });
            }
            break;
          }
        }
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        await send('error', { action: type, requestId, message: reason });
      }
    });

    panel.webview.html = readFileSync(vscode.Uri.joinPath(media, 'panel.html').fsPath, 'utf8')
      .replaceAll('{{csp}}', panel.webview.cspSource)
      .replaceAll('{{stylesheet}}', panel.webview.asWebviewUri(vscode.Uri.joinPath(media, 'panel.css')).toString())
      .replaceAll('{{script}}', panel.webview.asWebviewUri(vscode.Uri.joinPath(media, 'panel.js')).toString());
  }};
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('olinfoTrainer.workspace', provider,
    { webviewOptions: { retainContextWhenHidden: true } }));
}

module.exports = { activate };
