# Usare Olinfo Trainer Companion

## File del progetto

- `testo.pdf` contiene il testo nella lingua scelta, quando Training pubblica la traduzione.
- `testo.md` contiene il testo originale dei problemi Territoriali.
- `olinfo.xml` collega la cartella al problema e indica il linguaggio e il sorgente da inviare.
- `input.txt` e `output.txt` sono sempre presenti. Il plugin li precompila con la prima coppia di esempi riconoscibile negli allegati.
- Il sorgente usa il template pubblico quando disponibile. Per un problema C++ autonomo senza template il plugin genera `<id_problema>.cpp` con `int main()`. I progetti CMS usano `#include <bits/stdc++.h>` e quelli Territoriali usano `#include <iostream>`. Per Java viene conservato il nome del sorgente ufficiale o generato `Main.java`.
- Il plugin importa grader, stub e header C/C++ riconosciuti quando sono pubblici. Se serve un grader che Training non pubblica, la scheda ti avvisa.
- I progetti C/C++ autonomi o con grader pubblico includono `CMakeLists.txt`. Il template con `bits/stdc++.h` richiede libstdc++, disponibile ad esempio con GCC o MinGW-w64.

Il catalogo si aggiorna a ogni ricerca. Per i problemi autonomi puoi scegliere C++, C, Python, Java o Pascal. Per quelli con grader, la scelta dipende dagli allegati pubblici. Per i problemi CMS **Solo output**, il plugin scarica il PDF e genera i file di testo.

Esegui i programmi che leggono da `input.txt` e scrivono in `output.txt` dalla cartella del problema. Salva prima il risultato atteso se vuoi conservarlo. Per i problemi con ingresso e uscita standard puoi usare `./solution < input.txt` in Bash.

## Invii e account

La valutazione viene ricontrollata circa ogni cinque secondi quando è in corso. **Questo problema** mostra il miglior punteggio e fino a 20 invii recenti. **Tutti gli invii** apre lo storico sul sito. Quando lo storico è vuoto, il badge mostra **0 su 0**.

In JetBrains la foto compare accanto a **Olinfo**. In VS Code il badge usa l’icona account e mostra la foto nel fumetto e nel pannello account. Clicca il badge per vedere il profilo e usa **Esci** per chiudere la sessione nell’IDE.

L’IDE può inviare i problemi CMS che richiedono un solo sorgente. Per i problemi di solo output o che richiedono più file, usa il modulo sul sito.

## Territoriali

1. In **Nuovo problema**, scegli **Territoriali** dal menu **Formato**, cerca il problema e crea il progetto.
2. Accedi a Training e scegli **Esegui e invia** in **Questo problema** oppure premi l’icona **Upload**. L’IDE salva il sorgente, prende un input di Terry, esegue il programma e invia l’output con lo stesso sorgente.
3. La notifica mostra il punteggio e il numero di casi corretti. Apri **Vedi dettagli** o seleziona un invio in **Sottoposizioni** per vedere i casi corretti, errati e non inviati con i messaggi del correttore.

Il programma può leggere da standard input e scrivere su standard output oppure usare `input.txt` e `output.txt`. Il plugin esegue il sorgente in una cartella temporanea e gli esempi del progetto restano disponibili per le prove locali. Se Terry ha già un input valido per il problema, l’IDE lo riusa. Se è scaduto, ne richiede uno nuovo.

Usa un compilatore o interprete visibile all’IDE. Puoi impostare le variabili nella tabella con il percorso dell’eseguibile e poi riavviare l’IDE.

| Linguaggio | Strumento | Variabile |
| --- | --- | --- |
| C++ | `g++` o `clang++` con supporto C++20 | `CXX` |
| C | `gcc` o `clang` con supporto C17 | `CC` |
| Python | Python 3 | `OLINFO_PYTHON` |
| Java | JDK 11 o successivo. Il plugin riconosce anche `JAVA_HOME`. | `OLINFO_JAVA` |
| Pascal | Free Pascal (`fpc`) | `OLINFO_FPC` |

Il limite di tempo locale è di 120 secondi per compilazione o esecuzione. Training accetta fino a 100.000 byte per il sorgente e un massimo di 1 MiB per l’intera richiesta di invio.

## Collegare un progetto esistente

Crea `olinfo.xml` alla radice. Usa l’identificativo di Training, il nome effettivo del sorgente e uno tra `cpp`, `c`, `py`, `java` o `pas`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<olinfo version="1" task="easy2" language="cpp" source="easy2.cpp"/>
```

Per il formato Territoriali usa l’identificativo completo della pagina, ad esempio `task="terry/download"`.
