# Sviluppare e provare i plugin

Usa un JDK 21 per Gradle e Node.js 22 o superiore per l’estensione. Il wrapper Gradle è già incluso. La prima build JetBrains scarica l’IDE di sviluppo e richiede spazio per il download e l’estrazione.

## Visual Studio Code

Apri la radice del repository in VS Code ed esegui questi comandi in un terminale.

```sh
cd vscode
npm ci
npm test
```

Da **Run and Debug** avvia la configurazione **Olinfo Trainer (Extension Development Host)** con F5. La configurazione esegue il build e apre una seconda finestra. In quella finestra apri la palette e lancia **Olinfo Trainer: Nuovo problema**. Per iterare, usa **Developer: Reload Window** dopo aver rilanciato il build o dopo aver avviato `npm run watch`.

Per creare il pacchetto installabile, usa lo script npm.

```sh
npm run package
```

Il file `.vsix` viene scritto nella cartella `vscode/`. Puoi installarlo con `code --install-extension PERCORSO_DEL_FILE.vsix`.

## IntelliJ Platform, CLion e PyCharm

Esegui i test e avvia l’IDE di sviluppo dalla radice del repository.

```sh
bash ./gradlew :jetbrains:test :jetbrains:buildPlugin
bash ./gradlew :jetbrains:runIde
```

Su Windows usa `.\gradlew.bat`. Se Windows usa un JDK diverso, imposta `JAVA_HOME` alla cartella del JDK 21 prima di avviare il wrapper.

`runIde` apre IntelliJ IDEA Community in un profilo isolato. Dalla schermata iniziale scegli **New Project → Olinfo Trainer**. Prova anche la stessa voce da **File → New Project** e la scheda **Tools → Olinfo Trainer: Nuovo problema** con un progetto aperto. Per avviare il profilo isolato in CLion o PyCharm, imposta il prodotto e la versione come in questi comandi.

```sh
bash ./gradlew :jetbrains:runIde -PideType=CL -PideVersion=2024.3.6
bash ./gradlew :jetbrains:runIde -PideType=PY -PideVersion=2024.3.6
```

Il pacchetto `.zip` si trova in `jetbrains/build/distributions/`. Puoi installarlo in CLion o PyCharm da **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

`bash ./gradlew :jetbrains:verifyPlugin` verifica la compatibilità binaria del pacchetto con IntelliJ IDEA Community, CLion e PyCharm Professional 2024.3.6. I report sono in `jetbrains/build/reports/pluginVerifier/`.

## Verifiche

| Problema | Comportamento da provare |
| --- | --- |
| `oii_datazione` | C++, grader pubblico, esempi, scelta tra PDF italiano e inglese |
| `easy2` | Programma autonomo con esempi in ZIP e sorgente generato `easy2.cpp` |
| `oii_z` | Template pubblico e avviso per il grader non scaricabile |

Controlla anche una ricerca senza risultati, una cartella già esistente e il pulsante **Apri cartella**. Per C++ puoi validare il progetto generato con `cmake -S . -B build` e `cmake --build build`.

I test predefiniti usano fixture locali e non dipendono dal servizio esterno. Imposta `OLINFO_LIVE_TEST=1` per includere un test end-to-end con il catalogo reale. In PowerShell usa `$env:OLINFO_LIVE_TEST = '1'` prima di avviare la suite.

I test di invio simulano il login, il multipart e gli esiti della valutazione. Per una prova manuale, apri un progetto con `olinfo.xml`, accedi con il tuo account Training, verifica le linee guida e invia il sorgente dal menu **Olinfo**. Confronta l’esito e il miglior punteggio con la pagina **Sottoposizioni**. La prova manuale crea un invio nel tuo account.

## Struttura

| Percorso | Contenuto |
| --- | --- |
| `vscode/` | Extension Host Node.js e scheda Webview |
| `jetbrains/` | Plugin Java e scheda Swing |
| `shared/` | Configurazione del catalogo e fixture usate da entrambi |
| `.github/workflows/build.yml` | Test e creazione automatica dei due pacchetti |

I client interrogano la pagina pubblica `/tasks/{pagina}?search=…` e le pagine `/task/{id}`. I dettagli sono ricavati dal markup pubblico e il PDF viene richiesto con la locale scelta. La piattaforma non offre alle sessioni anonime un contratto stabile per questi dati. Se cambia il markup, aggiorna i due client e le fixture. Il [sorgente di CMSocial](https://github.com/olimpiadi-informatica/cmsocial) documenta il comportamento del sito.

Per l’accesso il plugin legge il riferimento del modulo di login pubblicato dal sito. L’invio usa `/files/submit` e ricava nome del campo e compilatore dal modulo autenticato. Il monitor legge `/task/{id}/submissions`, inizialmente ogni pochi secondi per gli invii in corso. Le fixture condivise permettono di aggiornare le due implementazioni insieme quando il sito cambia.

## Release

GitHub Actions esegue i test e carica i due pacchetti come artefatti sui push e sulle pull request. I job usano runner Ubuntu standard. [Costi di GitHub Actions](https://docs.github.com/en/billing/concepts/product-billing/github-actions#how-use-of-github-actions-is-measured)

Prima di pubblicare, allinea le versioni in `jetbrains/build.gradle.kts` e nel manifest VS Code. Per aggiornare insieme il manifest e il lockfile dell’estensione, usa `npm version X.Y.Z --no-git-tag-version` nella cartella `vscode/`. Dopo il commit su `master`, crea il tag `vX.Y.Z` e invialo con `git push origin vX.Y.Z`. Il workflow verifica le versioni nei pacchetti, crea la release e genera `updatePlugins.xml` dal descrittore JetBrains incluso nello ZIP.

Per pubblicare anche sul Visual Studio Marketplace, registra o seleziona il publisher e usa il suo identificativo in `vscode/package.json`. Configura Trusted Publishing per il repository, il workflow `build.yml` e l’ambiente GitHub `vscode-marketplace`. Infine, imposta la variabile del repository `PUBLISH_VSCODE_MARKETPLACE=true`. Le release successive useranno OIDC per pubblicare il VSIX già compilato. [Guida Microsoft a Trusted Publishing](https://code.visualstudio.com/api/working-with-extensions/publishing-extension#trusted-publishing)
