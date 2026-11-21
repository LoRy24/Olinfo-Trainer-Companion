# Olinfo Trainer Companion

Olinfo Trainer Companion crea una cartella di lavoro per un problema di [Training Olinfo](https://training.olinfo.it/) da Visual Studio Code o da un IDE JetBrains come CLion e PyCharm.

## Installazione

Scarica il pacchetto per il tuo IDE dalla [release più recente](https://github.com/LoRy24/Olinfo-Trainer-Companion/releases/latest). Per provare una build di sviluppo, scarica il relativo artefatto da [GitHub Actions](https://github.com/LoRy24/Olinfo-Trainer-Companion/actions) ed estrai l’archivio esterno.

| IDE | Pacchetto | Installazione |
| --- | --- | --- |
| Visual Studio Code Desktop 1.96+ | `.vsix` | **Extensions → … → Install from VSIX…** |
| IDE JetBrains su IntelliJ Platform, dalla versione 2024.3 | `.zip` | **Settings → Plugins → ⚙ → Install Plugin from Disk…** |

Puoi anche [compilare e avviare entrambi i plugin in locale](docs/SVILUPPO.md).

Per ricevere gli aggiornamenti in JetBrains, aggiungi questo URL in **Settings → Plugins → ⚙ → Manage Plugin Repositories…** e seleziona il repository personalizzato come fonte degli aggiornamenti se l’IDE lo chiede. [Guida JetBrains](https://www.jetbrains.com/help/clion/managing-plugins.html#custom-plugin-repositories)

```text
https://github.com/LoRy24/Olinfo-Trainer-Companion/releases/latest/download/updatePlugins.xml
```

Per VS Code, puoi installare il nuovo VSIX da GitHub Releases. Gli aggiornamenti automatici nell’IDE sono disponibili quando l’estensione è pubblicata nel Visual Studio Marketplace. [Guida alla pubblicazione](docs/SVILUPPO.md#release)

## Nuovo problema

1. In VS Code, apri la palette dei comandi e scegli **Olinfo Trainer: Nuovo problema**. In CLion, PyCharm e IntelliJ IDEA, scegli **New Project** dalla schermata iniziale o **File → New Project** da un progetto aperto. Seleziona **Olinfo Trainer** nell’elenco, nella sezione **Other** se presente. Da un progetto aperto puoi anche usare **Tools → Olinfo Trainer: Nuovo problema**.
2. Cerca per titolo o identificativo. Ogni ricerca, inclusa la navigazione tra le pagine, interroga il catalogo pubblico aggiornato.
3. Seleziona il problema, la lingua del PDF e il linguaggio di programmazione.
4. Scegli il nome del progetto e la cartella padre. Dal wizard JetBrains la creazione apre il progetto. Nella scheda aperta da **Tools** o in VS Code, premi **Crea progetto**, poi **Apri cartella**.

### File generati

- `testo.pdf` contiene la traduzione scelta tra quelle pubblicate per il problema.
- `input.txt` e `output.txt` sono sempre presenti. Se gli allegati contengono una coppia di esempi riconoscibile, i file vengono precompilati con la prima coppia. Negli altri casi sono vuoti.
- Il sorgente usa il template pubblico quando è disponibile. Per un problema C++ autonomo senza template, il plugin genera `<id_problema>.cpp` con `#include <bits/stdc++.h>`, `using namespace std;` e `int main()`. Per gli altri problemi C++ il sorgente si chiama `file.cpp`. Per Java viene conservato il nome del sorgente ufficiale o generato `Main.java`.
- Il grader o stub pubblico e gli header C/C++ riconosciuti vengono importati quando disponibili. Se il problema richiede un grader che Training non pubblica, la scheda ti avvisa.
- I progetti C/C++ con ingresso autonomo o con grader pubblico includono `CMakeLists.txt`, utilizzabile in CLion e da terminale. Per compilare il template con `bits/stdc++.h` serve una toolchain con libstdc++, come GCC o MinGW-w64.

Il menu propone C++, C, Python, Java e Pascal per i problemi a programma autonomo. Per i problemi con grader, i linguaggi si ricavano dai sorgenti e dai grader pubblici, poiché l’elenco ufficiale dei compilatori richiede l’accesso. Il catalogo copre i problemi CMS presenti in **Tutti i problemi**. Per un problema CMS di tipo **Solo output**, genera il PDF e i file di testo. Il catalogo Terry usa un flusso distinto, con testi spesso in Markdown e input personalizzati.

I problemi che leggono da `input.txt` e scrivono in `output.txt` vanno avviati con la cartella del problema come directory di lavoro. Se `output.txt` contiene un risultato atteso che vuoi conservare, salvalo prima di eseguire la soluzione. Nei problemi standard input/output puoi usare, ad esempio, `./solution < input.txt` in Bash.

La creazione usa una cartella nuova e protegge i file esistenti. L’importazione si serve delle pagine e degli allegati pubblici di Training Olinfo e non richiede credenziali. L’invio delle soluzioni è fuori dallo scope di questa versione.

## Collaborare

Leggi la [guida allo sviluppo](docs/SVILUPPO.md), le [linee guida per contribuire](CONTRIBUTING.md), il [codice di condotta](CODE_OF_CONDUCT.md) e la [politica di sicurezza](SECURITY.md).

Il codice è rilasciato sotto [licenza MIT](LICENSE). Il progetto è indipendente dalle Olimpiadi Italiane di Informatica. L’icona adatta il loro logo, con [attribuzione e licenza dell’asset](assets/oii/NOTICE.md).
