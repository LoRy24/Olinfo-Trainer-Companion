const { spawn } = require('node:child_process');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

const windows = process.platform === 'win32';
const maxOutput = 1_000_000;
const diagnosticLimit = 16 * 1024 * 1024;

function candidates(variable, defaults) {
  return process.env[variable] ? [[process.env[variable]]] : defaults;
}

async function execute(commands, args, directory, label, input, toolDirectory) {
  const stdout = path.join(directory, 'stdout.txt');
  const stderr = path.join(directory, 'stderr.txt');
  const handles = [];
  try {
    const out = await fs.open(stdout, 'w');
    handles.push(out);
    const err = await fs.open(stderr, 'w');
    handles.push(err);
    const stdin = input && await fs.open(input, 'r');
    if (stdin) handles.push(stdin);
    const env = { ...process.env };
    if (toolDirectory) {
      const pathKey = Object.keys(env).find(key => key.toUpperCase() === 'PATH') || 'PATH';
      env[pathKey] = toolDirectory + path.delimiter + (env[pathKey] || '');
    }
    for (const [program, ...prefix] of commands) {
      const child = spawn(program, [...prefix, ...args], {
        cwd: directory, env, windowsHide: true, stdio: [stdin?.fd ?? 'ignore', out.fd, err.fd],
      });
      let failure;
      const stop = reason => {
        failure ||= reason;
        child.kill('SIGKILL');
      };
      const timeout = setTimeout(() => stop('Tempo limite superato (120 secondi).'), 120_000);
      const monitor = setInterval(() => {
        for (const filename of [stdout, stderr, path.join(directory, 'output.txt')]) {
          void fs.lstat(filename).then(info => {
            if (info.size > diagnosticLimit) stop('Il programma ha prodotto troppi dati.');
          }).catch(() => {});
        }
      }, 250);
      let code;
      try {
        code = await new Promise((resolve, reject) => {
          child.once('error', reject);
          child.once('close', resolve);
        });
      } catch (error) {
        if (error.code === 'ENOENT') continue;
        throw error;
      } finally {
        clearTimeout(timeout);
        clearInterval(monitor);
      }
      if (failure) throw new Error(label + ' · ' + failure);
      if (code !== 0) {
        const diagnostic = await fs.open(stderr, 'r');
        try {
          const preview = Buffer.alloc(1800);
          const { bytesRead } = await diagnostic.read(preview, 0, preview.length);
          const message = preview.subarray(0, bytesRead).toString('utf8').trim();
          throw new Error(label + ' non riuscita (uscita ' + (code ?? 'interrotta') + ').' + (message ? '\n' + message : ''));
        } finally {
          await diagnostic.close();
        }
      }
      return program;
    }
    throw new Error('Strumento non trovato (' + commands.map(([program]) => program).join(', ') + '). Verifica il PATH dell’IDE.');
  } finally {
    await Promise.all(handles.map(handle => handle.close()));
  }
}

async function runtime(commands, directory, label, isSupported) {
  for (const command of commands) {
    try {
      await execute([command], ['--version'], directory, label);
      const version = await fs.readFile(path.join(directory, 'stdout.txt'), 'utf8');
      if (isSupported(version.trim())) return [command];
    } catch { /* Check the next configured or installed executable. */ }
  }
  throw new Error(label + ' non disponibile. Controlla il PATH e le impostazioni dell’IDE.');
}

async function runTerry(link, source, getInput, progress = () => {}, configuredJdk) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'olinfo-terry-'));
  try {
    const solution = path.join(directory, link.source);
    const inputFile = path.join(directory, 'input.txt');
    const executable = path.join(directory, windows ? 'solution.exe' : 'solution');
    await fs.writeFile(solution, source, { flag: 'wx' });
    let command = [[executable]];
    let compiler;
    let args = [];
    const language = link.language;
    if (language === 'cpp' || language === 'c') {
      const cpp = language === 'cpp';
      progress('Compilazione locale…');
      compiler = await execute(candidates(cpp ? 'CXX' : 'CC', cpp ? [['g++'], ['clang++']] : [['gcc'], ['clang']]),
        [cpp ? '-std=c++20' : '-std=c17', '-O2', solution, '-o', executable], directory, 'Compilazione');
    } else if (language === 'pas') {
      progress('Compilazione locale…');
      compiler = await execute(candidates('OLINFO_FPC', [['fpc']]), ['-O2', '-o' + executable, solution], directory, 'Compilazione');
    } else if (language === 'py') {
      const defaults = windows ? [['py', '-3'], ['python'], ['python3']] : [['python3'], ['python']];
      command = await runtime(candidates('OLINFO_PYTHON', defaults), directory, 'Python 3', version => /^Python 3\./.test(version));
      args = [solution];
    } else if (language === 'java') {
      const defaults = [configuredJdk, process.env.JAVA_HOME].filter(Boolean)
        .map(home => [path.join(home, 'bin', windows ? 'java.exe' : 'java')]);
      defaults.push(['java']);
      command = await runtime(candidates('OLINFO_JAVA', defaults), directory, 'JDK 11 o successivo',
        version => Number(/^(?:java|openjdk) (\d+)/.exec(version)?.[1]) >= 11);
      args = ['-Dfile.encoding=UTF-8', solution];
    } else {
      throw new Error('Linguaggio Territoriali non supportato.');
    }
    progress('Preparazione e download dell’input…');
    const input = await getInput();
    await fs.writeFile(inputFile, input.bytes, { flag: 'wx' });
    progress('Esecuzione locale…');
    await execute(command, args, directory, 'Esecuzione', inputFile,
      windows && compiler && path.isAbsolute(compiler) ? path.dirname(compiler) : undefined);
    const fileOutput = path.join(directory, 'output.txt');
    const result = await fs.lstat(fileOutput).catch(error => {
      if (error.code !== 'ENOENT') throw error;
    });
    const outputPath = result ? fileOutput : path.join(directory, 'stdout.txt');
    const outputInfo = result ?? await fs.lstat(outputPath);
    if (!outputInfo.isFile() || outputInfo.size > maxOutput) throw new Error('Il file di output supera il limite di 1.000.000 byte o non è valido.');
    const output = await fs.readFile(outputPath);
    if (!output.length || output.length > maxOutput || !output.toString('utf8').trim()) {
      throw new Error('Il programma deve produrre un output non vuoto entro 1.000.000 byte.');
    }
    return { input, output };
  } finally {
    await fs.rm(directory, { recursive: true, force: true, maxRetries: 3 }).catch(() => {});
  }
}

module.exports = { runTerry };
