# Politica di sicurezza

Le correzioni di sicurezza vengono applicate al branch principale e all’ultima versione disponibile.

Per segnalare una vulnerabilità, usa **Security → Advisories → Report a vulnerability** quando la segnalazione privata è abilitata. In alternativa contatta privatamente una persona responsabile del repository. Includi la versione, i passaggi per riprodurre il problema e l’impatto osservato. Evita di inviare token, dati di utenti o progetti privati.

I plugin comunicano con Training Olinfo via HTTPS. Dopo l’accesso conservano il token di sessione nel portachiavi dell’IDE. La password è usata per la richiesta di accesso. Il file `olinfo.xml` contiene solo metadati del problema e il nome del sorgente.

Per inviare una soluzione serve una conferma esplicita del file e del problema. Il plugin legge il sorgente dalla cartella del progetto e l’invio richiede un progetto attendibile nell’IDE. La compilazione e l’esecuzione in locale avvengono su iniziativa dell’utente.
