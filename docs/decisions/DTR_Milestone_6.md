
## [M6] JavaFX over Swing for MainWindow

**Context**: Swing is abandoned and visually dated. JavaFX is actively maintained,
CSS-styleable, and designed for the scene-graph model.

**Decision**: JavaFX for all `MainWindow` components. AWT retained only for
`SystemTray` / `TrayIcon` — no JavaFX equivalent exists.

Coexistence: `Platform.setImplicitExit(false)`. All JavaFX updates from AWT threads
via `Platform.runLater()`.

**Discarded**: Swing — no CSS theming, no scene graph, no active development.

**Motivation**: CSS theming is a first-class `PrismUI` requirement. JavaFX delivers it
natively.

---

## [M6] MainWindow — six tabs, contextual opening

**Context**: MainWindow must serve non-technical users (dashboard) and advanced users
(per-vault config, log). Contextual opening from ContextMenu reduces navigation steps.

**Decision**: single `TabPane` with six tabs:
```
[ 🏠 Home ]  [ Properties ]  [ Log ]  [ Conflicts ]  [ Backup ]  [ ⚙ Settings ]
```
Vault switcher (`ComboBox` autocomplete) always visible in toolbar.
Contextual opening from AWT thread:
```java
Platform.runLater(() -> mainWindow.openTab(Tab.LOG, vaultId));
```

Conflict resolution dialog in Tab Conflicts:
```
Have you copied the remote changes you wanted to keep?
Once confirmed, the remote version will be deleted.

[ Cancel ]    [ Yes, I'm done ]
```

---

## [M6] PrismUI — shared JavaFX design system, Maven Central candidate

**Context**: ObsidianSync is the first product in a planned family of Java desktop
applications. Shared JavaFX library enables consistent theming.

**Decision**: separate Maven project `prism-ui`. Three themes via CSS swap:
```java
scene.getStylesheets().clear();
scene.getStylesheets().add(theme.cssPath());
```
Themes: Default, Retro terminal, Zen minimal.
Name to be decided in naming session — leading candidate **PrismUI**.

**Motivation**: CSS theming in JavaFX is a single method call. Impossible in Swing
without third-party LAF libraries.

---

## [M6] i18n — 10 languages, ResourceBundle

**Decision**: 10 locale files covering ~75% of global internet users:
English, Mandarin, Hindi, Spanish, Arabic, Portuguese, French, German, Japanese, Italian.

RTL (Arabic): `root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT)` — propagates
to all children automatically in JavaFX.