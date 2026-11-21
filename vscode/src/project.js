const fs = require('node:fs/promises');
const path = require('node:path');
const { isHeader, samplePair } = require('./training');

const native = new Set(['c', 'cpp']);

function projectPath(parent, name) {
  if (typeof parent !== 'string' || !path.isAbsolute(parent)) throw new Error('Scegli una cartella di destinazione.');
  if (typeof name !== 'string' || !name || name.length > 100 || name !== name.trim()
      || name.endsWith('.') || /[<>:"/\\|?*\x00-\x1f]/.test(name)
      || /^(?:con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\.|$)/i.test(name)) {
    throw new Error('Inserisci un nome cartella valido (fino a 100 caratteri).');
  }
  return path.join(parent, name);
}

function starter(language, mode) {
  const c = mode === 'file' ? '    freopen("input.txt", "r", stdin);\n    freopen("output.txt", "w", stdout);\n' : '';
  const templates = {
    cpp: '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n' + c + '    // Scrivi qui la soluzione.\n    return 0;\n}\n',
    c: '#include <stdio.h>\n\nint main(void) {\n' + c + '    /* Scrivi qui la soluzione. */\n    return 0;\n}\n',
    py: mode === 'file'
      ? 'import sys\n\nwith open("input.txt") as sys.stdin, open("output.txt", "w") as sys.stdout:\n    # Scrivi qui la soluzione.\n    pass\n'
      : '# Scrivi qui la soluzione.\n',
    java: mode === 'file'
      ? 'import java.io.*;\n\npublic class Main {\n    public static void main(String[] args) throws IOException {\n        try (var in = new BufferedInputStream(new FileInputStream("input.txt"));\n             var out = new PrintStream("output.txt")) {\n            // Scrivi qui la soluzione usando in e out.\n        }\n    }\n}\n'
      : 'public class Main {\n    public static void main(String[] args) {\n        // Scrivi qui la soluzione.\n    }\n}\n',
    pas: mode === 'file'
      ? "program Solution;\nbegin\n  Assign(Input, 'input.txt'); Reset(Input);\n  Assign(Output, 'output.txt'); Rewrite(Output);\n  { Scrivi qui la soluzione. }\nend.\n"
      : 'program Solution;\nbegin\n  { Scrivi qui la soluzione. }\nend.\n',
  };
  if (mode === 'grader') {
    return language === 'pas' ? '{ Consulta il PDF per la firma richiesta dal grader. }\n'
      : (language === 'py' ? '# ' : '// ') + 'Consulta il PDF per la firma richiesta dal grader.\n';
  }
  return templates[language];
}

function cmake(language, source, grader) {
  const compiler = language === 'cpp' ? 'CXX' : 'C';
  const standard = language === 'cpp' ? 20 : 17;
  return 'cmake_minimum_required(VERSION 3.16)\nproject(olinfo_solution LANGUAGES ' + compiler + ')\n'
    + 'set(CMAKE_' + compiler + '_STANDARD ' + standard + ')\n'
    + 'set(CMAKE_' + compiler + '_STANDARD_REQUIRED ON)\n'
    + 'add_executable(solution "' + source + '"' + (grader ? ' "' + grader + '"' : '') + ')\n'
    + 'set_property(TARGET solution PROPERTY VS_DEBUGGER_WORKING_DIRECTORY "\${CMAKE_SOURCE_DIR}")\n';
}

async function createProject(client, details, language, locale, parent, name) {
  const target = projectPath(parent, name);
  const option = details.options.find(item => item.id === language);
  if (!option) throw new Error('Linguaggio non disponibile per questo problema.');
  if (!(await fs.stat(parent)).isDirectory()) throw new Error('La destinazione deve essere una cartella.');
  try {
    await fs.lstat(target);
    throw new Error('Esiste già un file o una cartella con questo nome.');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }

  const examples = samplePair(details.files);
  const [pdf, input, output, solution, grader] = await Promise.all([
    client.pdf(details, locale), client.asset(examples.input), client.asset(examples.output),
    client.asset(option.starter), client.asset(option.grader),
  ]);
  const content = new Map([['input.txt', input], ['output.txt', output], ['testo.pdf', pdf]]);
  let solutionName;
  if (language !== 'output') {
    solutionName = language === 'java' ? option.starter?.name || 'Main.java' : 'file.' + language;
    if (language === 'cpp' && details.mode !== 'grader' && !option.starter && !option.grader) {
      solutionName = details.id + '.cpp';
    }
    if (!/^[a-z0-9_][a-z0-9_.-]*$/i.test(solutionName)) throw new Error('Nome del sorgente non valido.');
    const graderName = option.grader && option.grader.name;
    content.set(solutionName, option.starter ? solution : starter(language, details.mode));
    if (graderName) content.set(graderName, grader);
    if (native.has(language)) {
      for (const file of details.files.filter(file => isHeader(file.name))) {
        if (!content.has(file.name)) content.set(file.name, await client.asset(file));
      }
      if (details.mode !== 'grader' || graderName) {
        content.set('CMakeLists.txt', cmake(language, solutionName, graderName));
      }
    }
  }

  // mkdir and exclusive writes make the final check safe even across two extension hosts.
  try {
    await fs.mkdir(target);
  } catch (error) {
    if (error.code === 'EEXIST') throw new Error('Esiste già un file o una cartella con questo nome.');
    throw error;
  }
  const created = [];
  try {
    for (const [file, data] of content) {
      const location = path.join(target, file);
      const handle = await fs.open(location, 'wx');
      created.push(location);
      try { await handle.writeFile(data); } finally { await handle.close(); }
    }
  } catch (error) {
    await Promise.allSettled(created.map(file => fs.unlink(file)));
    await fs.rmdir(target).catch(() => {});
    throw error;
  }
  return { target, solutionName };
}

module.exports = { createProject, projectPath };
