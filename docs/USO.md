# Usare Olinfo Trainer Companion

## File del progetto

- `testo.pdf` contiene il testo nella lingua scelta, quando Training pubblica la traduzione.
- `olinfo.xml` collega la cartella al problema e indica il linguaggio e il sorgente da inviare.
- `input.txt` e `output.txt` sono sempre presenti. Il plugin li precompila con la prima coppia di esempi riconoscibile negli allegati.
- Il sorgente usa il template pubblico quando disponibile. Per un problema C++ autonomo senza template, il plugin genera `<id_problema>.cpp` con `#include <bits/stdc++.h>`, `using namespace std;` e `int main()`. Gli altri sorgenti C++ si chiamano `file.cpp`. Per Java viene conservato il nome del sorgente ufficiale o generato `Main.java`.
- Il plugin importa grader, stub e header C/C++ riconosciuti quando sono pubblici. Se serve un grader che Training non pubblica, la scheda ti avvisa.
- I progetti C/C++ autonomi o con grader pubblico includono `CMakeLists.txt`. Il template con `bits/stdc++.h` richiede libstdc++, disponibile ad esempio con GCC o MinGW-w64.

Il catalogo si aggiorna a ogni ricerca. Per i problemi autonomi puoi scegliere C++, C, Python, Java o Pascal. Per quelli con grader, la scelta dipende dagli allegati pubblici. Il catalogo copre i problemi CMS in **Tutti i problemi**. Per i problemi **Solo output**, il plugin scarica il PDF e genera i file di testo. I problemi Terry seguono un flusso distinto sul sito.

Esegui i programmi che leggono da `input.txt` e scrivono in `output.txt` dalla cartella del problema. Salva prima il risultato atteso se vuoi conservarlo. Per i problemi con ingresso e uscita standard puoi usare `./solution < input.txt` in Bash.

## Invii e account

La valutazione viene ricontrollata circa ogni cinque secondi quando è in corso. **Questo problema** mostra il miglior punteggio e fino a 20 invii recenti. **Tutti gli invii** apre lo storico sul sito. Quando lo storico è vuoto, il badge mostra **0 su 0**.

In JetBrains la foto compare accanto a **Olinfo**. In VS Code il badge usa l’icona account e mostra la foto nel fumetto e nel pannello account. Clicca il badge per vedere il profilo e usa **Esci** per chiudere la sessione nell’IDE.

L’IDE può inviare i problemi CMS che richiedono un solo sorgente. Per i problemi di solo output o che richiedono più file, usa il modulo sul sito.

## Collegare un progetto esistente

Crea `olinfo.xml` alla radice. Usa l’identificativo di Training, il nome effettivo del sorgente e uno tra `cpp`, `c`, `py`, `java` o `pas`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<olinfo version="1" task="easy2" language="cpp" source="easy2.cpp"/>
```