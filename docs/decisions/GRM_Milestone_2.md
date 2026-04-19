# DTR — SyncOrchestrator

---

## [M2] Architettura event-driven per SyncOrchestrator

**Contesto**: SyncOrchestrator deve coordinare operazioni Git provenienti da chiamanti multipli (Task Scheduler logon/logoff, AutosaveScheduler, tray icon). Git è seriale — operazioni concorrenti sullo stesso repo causano conflitti.

**Decisione**: architettura event-driven con coda a priorità. I chiamanti pubblicano eventi, l'orchestratore consuma dalla coda in modo seriale.

**Motivazione**: la concorrenza è gestita in un solo posto; i chiamanti sono disaccoppiati dall'orchestratore; il pattern prepara il mindset per i microservizi.

**Alternative scartate**:

- Chiamate dirette (`orchestrator.pull()`) — ogni chiamante deve conoscere l'orchestratore, la gestione della concorrenza si distribuisce

---

## [M2] Scala di priorità degli eventi

**Contesto**: eventi di tipo diverso possono coesistere in coda simultaneamente. È necessario un ordinamento che rifletta l'importanza relativa delle operazioni.

**Decisione**:

|Priorità|Evento|
|---|---|
|1|Pull logon|
|2|Push manuale|
|3|Push logoff|
|4|Autosave|

**Motivazione**: il pull è precondizione di tutto il resto. Il push manuale riflette intenzione esplicita dell'utente e precede il logoff. L'autosave è tollerante e cedevole.

---

## [M2] Strategia di deduplicazione: latest wins

**Contesto**: eventi dello stesso tipo possono accumularsi in coda (es. autosave ogni 15 minuti, doppio click sulla tray).

**Decisione**: un evento in coda viene sostituito dal più recente dello stesso tipo. Un evento in esecuzione non viene interrotto — il nuovo evento attende in coda.

**Motivazione**: un autosave rappresenta uno snapshot del momento attuale, non un'operazione incrementale. Accodarne due non aggiunge valore.

---

## [M2] Retry con exponential backoff

**Contesto**: operazioni Git possono fallire per assenza di connessione. Un retry illimitato satura la coda; un retry fisso martella una risorsa non disponibile.

**Decisione**: exponential backoff con massimo 3 tentativi. Delay progressivo: 30s → 60s → 120s. Dopo il terzo fallimento l'evento viene scartato.

**Motivazione**: pattern standard nei sistemi distribuiti. Evita di sovraccaricare una risorsa non disponibile. Tempo massimo di attesa (~3.5 minuti) accettabile per un pull al logon.

**Rischi accettati**: se la rete è assente per tutta la sessione, il pull al logon fallisce definitivamente. Mitigato dal hook di notifica.

---

## [M2] Hook di notifica come dependency inversion

**Contesto**: i fallimenti di priorità 1 (pull logon) devono essere comunicati all'utente. La tray icon è fuori scope per questo sprint.

**Decisione**: l'orchestratore espone un'interfaccia `NotificationHook` con implementazione di default che scrive su log. La tray si aggancia in seguito implementando la stessa interfaccia senza modificare l'orchestratore.

**Motivazione**: dependency inversion — l'orchestratore dipende dall'astrazione, non dall'implementazione. Prepara l'architettura per la tray senza bloccare lo sprint corrente.

**Stato**: interfaccia da scaffoldare, implementazione tray in backlog.

---

## [M2] Separazione commit locale / push remoto
Contesto: autosave è un checkpoint frequente e silenzioso. Push manuale e logoff sono operazioni di sincronizzazione con il remoto. Trattarle allo stesso modo esporrebbe l'autosave a fallimenti di rete non necessari.
Decisione:

AUTOSAVE → commit locale only
PUSH_MANUAL / PUSH_LOGOFF → commit locale + push remoto

Motivazione: resilienza — l'autosave funziona anche senza rete. Separazione delle responsabilità — il commit locale è sempre disponibile, il push remoto è una operazione distinta e fallibile. Il retry con exponential backoff si applica solo alle operazioni che toccano il remoto.