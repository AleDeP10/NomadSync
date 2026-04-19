# Decision Track Record

## [M1] Sincronizzazione via Git invece di OneDrive
**Contesto**: necessità di sincronizzare il vault Obsidian tra più computer Windows.
OneDrive è incompatibile con Obsidian per via delle modifiche asincrone e concorrenti ai file.
**Decisione**: Git + GitHub
**Motivazione**: le operazioni di pull/push sono atomiche e sincrone; il diff nativo consente autosave differenziale; nessun processo esterno tocca i file mentre Obsidian è aperto.
**Alternative scartate**:
- OneDrive diretta: causa conflitti per scritture concorrenti
- Syncthing: P2P, richiede che almeno un device sia sempre acceso
- Obsidian Sync: ufficiale ma a pagamento (~4$/mese)

---

## [M1] Strategia `theirs` per la risoluzione dei conflitti in pull
**Contesto**: il pull avviene automaticamente al logon, senza supervisione utente. In caso di conflitto Git si bloccherebbe chiedendo intervento manuale.
**Decisione**: `git pull -X theirs` — in caso di conflitto vince sempre la versione remota.
**Motivazione**: il repository remoto rappresenta l'ultima versione salvata consapevolmente tramite logoff. È la fonte di verità. Ogni sovrascrittura viene tracciata nel log.
**Rischi accettati**: modifiche locali non committate prima del push potrebbero essere perse in caso di conflitto. Mitigato da autosave periodico e da `git stash` prima del pull.

---

## [M1] Sequenza logon: stash → pull → stash pop
**Contesto**: se al logon sono presenti modifiche locali non committate, `git pull` si rifiuta di procedere.
**Decisione**: eseguire `git stash` prima del pull e `git stash pop` dopo.
**Motivazione**: preserva le modifiche locali durante il pull senza richiedere un commit esplicito. Comportamento trasparente per l'utente.
**Alternativa scartata**: `git reset --hard` prima del pull — distrugge le modifiche locali senza possibilità di recupero.

---

## [M1] Aggancio logon/logoff tramite Task Scheduler invece di gpedit.msc
**Contesto**: necessità di eseguire script automaticamente al logon e logoff di Windows.
**Decisione**: Task Scheduler (`taskschd.msc`)
**Motivazione**: disponibile su tutte le edizioni di Windows (inclusa Home); interfaccia ispezionabile; cronologia delle esecuzioni; esportazione task in XML per replicabilità su altri PC.
**Alternativa scartata**: Group Policy Editor (`gpedit.msc`) — non disponibile su Windows Home; meno flessibile per il timer periodico.

---

## [M1] Autosave differenziale tramite `git diff --quiet`
**Contesto**: il salvataggio automatico temporizzato non deve generare commit vuoti se non ci sono modifiche.
**Decisione**: usare `git diff --quiet` come guard — exit code 0 significa nessuna modifica, exit code 1 significa modifiche presenti.
**Motivazione**: nativo Git, zero dipendenze aggiuntive, semantica chiara tramite exit code.

---

## [M1] Fat JAR tramite `maven-assembly-plugin`
**Contesto**: il JAR deve essere eseguibile standalone da Task Scheduler e da linea di comando, senza richiedere classpath esterni.
**Decisione**: `maven-assembly-plugin` con descriptor `jar-with-dependencies`.
**Motivazione**: produce un singolo artefatto autonomo; semplifica il deploy su più macchine — basta copiare la cartella `target/`.

---

## [M1] Risorse copiate in `target/` tramite `maven-resources-plugin`
**Contesto**: `ObsidianSync.bat` e `config.properties` devono trovarsi a fianco del JAR per essere risolti con path relativi.
**Decisione**: `maven-resources-plugin` copia i file da `src/main/resources/` a `target/` durante la fase `package`.
**Motivazione**: la struttura di deploy è autocontenuta in `target/`; nessuna configurazione manuale post-build.

---

## [M1] Due file di configurazione separati per ambiente
**Contesto**: le properties contengono credenziali Git (token GitHub) che non devono essere committate.
**Decisione**: `config.dev.properties` e `config.prod.properties` esclusi da `.gitignore`; `config.properties.template` committato come riferimento.
**Motivazione**: separazione netta tra configurazione e codice; sicurezza delle credenziali; onboarding facilitato tramite il template.

---

## [M1] Logging thread-safe tramite `synchronized`
**Contesto**: `AutosaveScheduler` gira su un thread separato e potrebbe scrivere sul log contemporaneamente al thread principale.
**Decisione**: metodo `log()` di `LogService` dichiarato `synchronized`.
**Motivazione**: soluzione semplice e corretta per il livello di concorrenza atteso (due thread al massimo). Overhead trascurabile per operazioni di I/O su file.
**Alternative future**: `ReentrantLock` o `BlockingQueue` con thread dedicato alla scrittura, se la concorrenza aumentasse.

---

## [M1] `SyncOrchestrator` come layer intermedio tra `Main` e `GitService`
**Contesto**: la logica di coordinamento delle operazioni (es. stash prima del pull, gestione exit code) non appartiene né a `Main` né a `GitService`.
**Decisione**: introdurre `SyncOrchestrator` come layer dedicato. `Main` chiamerà l'orchestratore; `GitService` eseguirà solo i singoli comandi git.
**Motivazione**: separazione delle responsabilità; `GitService` resta testabile in isolamento; la logica di business è centralizzata e non duplicata.
**Stato**: scaffolding completato, implementazione pianificata per Milestone 2.
