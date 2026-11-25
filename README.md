<h1 align="center">
  <img src="docs/branding/readme-banner.png" alt="Olinfo Trainer Companion" width="720">
</h1>

<p align="center">
  <a href="https://github.com/LoRy24/Olinfo-Trainer-Companion/releases/latest"><img src="https://img.shields.io/github/v/release/LoRy24/Olinfo-Trainer-Companion?style=flat&amp;logo=github&amp;label=release&amp;labelColor=243248&amp;color=237648" alt="Ultima release"></a>
  <a href="https://github.com/LoRy24/Olinfo-Trainer-Companion/actions/workflows/build.yml?query=branch%3Amaster"><img src="https://img.shields.io/github/actions/workflow/status/LoRy24/Olinfo-Trainer-Companion/build.yml?branch=master&amp;event=push&amp;style=flat&amp;logo=githubactions&amp;logoColor=white&amp;label=build&amp;labelColor=243248" alt="Stato della build su master"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/LoRy24/Olinfo-Trainer-Companion?style=flat&amp;label=licenza&amp;labelColor=243248&amp;color=496382" alt="Licenza MIT"></a>
</p>

Crea progetti per [Training Olinfo](https://training.olinfo.it/) in **VS Code**, **CLion**, **IntelliJ IDEA** e **PyCharm**. Scarica testo e sorgenti, invia la soluzione e segui la valutazione dall’IDE.

[Scarica l’ultima release](https://github.com/LoRy24/Olinfo-Trainer-Companion/releases/latest)

## Installazione

### Visual Studio Code

1. Scarica il file **`.vsix`** dalla release. Serve VS Code Desktop 1.96 o successivo.
2. Apri **Extensions**, fai clic su **…** e scegli **Install from VSIX…**.
3. Seleziona il file scaricato e ricarica la finestra se richiesto. L’icona **Olinfo Trainer** appare nella barra laterale.

Per aggiornare, installa il nuovo `.vsix` allo stesso modo. [Guida di VS Code](https://code.visualstudio.com/docs/configure/extensions/extension-marketplace#_install-from-a-vsix)

### CLion, IntelliJ IDEA e PyCharm

1. Scarica il file **`.zip`** dalla release e lascialo compresso. Serve un IDE JetBrains 2024.3 o successivo.
2. Apri **Settings → Plugins → ⚙ → Install Plugin from Disk…**. Su macOS apri **Settings** dal menu dell’IDE.
3. Seleziona lo ZIP e riavvia se richiesto. Da **New Project** scegli **Olinfo Trainer**, nella sezione **Other** se presente.

Per ricevere gli aggiornamenti nell’IDE, apri **Settings → Plugins → ⚙ → Manage Plugin Repositories…** e aggiungi questo indirizzo. [Guida JetBrains](https://www.jetbrains.com/help/idea/managing-plugins.html#custom-plugin-repositories)

```text
https://github.com/LoRy24/Olinfo-Trainer-Companion/releases/latest/download/updatePlugins.xml
```

## Crea un progetto

Apri **Olinfo Trainer → Nuovo problema**, scegli il formato e cerca per nome o identificativo. Il progetto contiene il testo, `input.txt`, `output.txt`, `olinfo.xml`, il sorgente e gli eventuali allegati pubblici. [Dettagli sui file generati](docs/USO.md)

![Catalogo di Training Olinfo e creazione di un progetto in CLion](docs/screenshots/clion-nuovo-problema.png)

## Invia e segui la valutazione

1. Apri la cartella con `olinfo.xml` alla radice. Fai clic su **Olinfo** nella barra di stato e accedi a Training.
2. Premi l’icona **Upload** accanto al punteggio, oppure **Invia soluzione** nella scheda **Questo problema**. Controlla il problema e il file nel dialogo, poi scegli **Invia a Training**.
3. La rotellina segnala l’invio e la valutazione. Il bollino a destra dei punti usa i colori di Training. Nella scheda **Questo problema** trovi gli invii recenti e il collegamento allo storico completo.

![Problema corrente, miglior punteggio e storico degli invii in CLion](docs/screenshots/clion-invii.png)

Per il primo invio, completa la registrazione e accetta le [linee guida di Training](https://training.olinfo.it/guidelines).

Per i problemi in formato **Territoriali**, scegli **Esegui e invia**. L’IDE richiede o recupera l’input di Terry, esegue il programma in locale e invia il sorgente insieme all’output. L’esito mostra punti ottenuti, totale e risultato di ciascun caso di test. [Requisiti per l’esecuzione locale](docs/USO.md#territoriali)

## Account Olinfo

Il badge **Olinfo** apre il pannello account con foto, punti totali, problemi risolti e posizione in classifica.

![Pannello Account Olinfo aperto dalla barra di stato di CLion](docs/screenshots/clion-account.png)

## Sviluppo e contributi

[Guida allo sviluppo](docs/SVILUPPO.md) · [Contribuire](CONTRIBUTING.md) · [Codice di condotta](CODE_OF_CONDUCT.md) · [Sicurezza](SECURITY.md) · [Licenza MIT](LICENSE)

Il progetto è indipendente dalle Olimpiadi Italiane di Informatica. L’icona adatta il logo OII. [Attribuzione](assets/oii/NOTICE.md)
