package io.github.olinfolabs.trainer;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.impl.TrustedProjects;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class SubmissionUi implements Disposable {
    private static final CredentialAttributes SESSION = new CredentialAttributes("Olinfo Trainer Companion / training.olinfo.it");

    private record Pending(String task, String id, String session, long until) {}
    record Snapshot(String text, String tooltip, String task, String newestId) {}

    private final Project project;
    private final SubmissionClient api = new SubmissionClient();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final java.util.concurrent.ScheduledFuture<?> timer;
    private volatile Snapshot snapshot = new Snapshot("", "", null, null);
    private volatile Pending pending;
    private volatile long nextRefresh;
    private volatile boolean disposed;
    private boolean working;

    public SubmissionUi(Project project) {
        this.project = project;
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                () -> refresh(false), 1, 5, TimeUnit.SECONDS);
    }

    Snapshot snapshot() { return snapshot; }

    private ProjectLink projectLink() throws IOException {
        String base = project.getBasePath();
        return base == null || !TrustedProjects.isTrusted(project) ? null : ProjectLink.read(Path.of(base));
    }

    void refresh(boolean immediate) {
        if (immediate) nextRefresh = 0;
        if (disposed || System.currentTimeMillis() < nextRefresh || !refreshing.compareAndSet(false, true)) return;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                nextRefresh = System.currentTimeMillis() + 45_000;
                ProjectLink link = projectLink();
                if (link == null) {
                    publish(new Snapshot("", "", null, null));
                    return;
                }
                String session = PasswordSafe.getInstance().getPassword(SESSION);
                var result = api.results(link.task(), session);
                Pending tracking = pending;
                boolean same = tracking != null && tracking.task().equals(link.task()) && tracking.session().equals(session);
                var latest = same ? result.rows().stream().filter(row -> row.id().equals(tracking.id())).findFirst().orElse(null)
                        : result.rows().stream().findFirst().orElse(null);
                boolean waiting = latest == null ? same && System.currentTimeMillis() < tracking.until() : latest.pending();
                if (waiting && (!same || System.currentTimeMillis() < tracking.until())) {
                    nextRefresh = System.currentTimeMillis() + 5_000;
                }
                if (same && latest != null && !latest.pending()) pending = null;
                String best = result.best() == null ? "—" : result.best() + " pt";
                String tooltip = link.task() + " · Miglior punteggio: " + best
                        + (latest == null ? "" : " · Ultimo invio #" + latest.id() + " · " + latest.outcome());
                publish(new Snapshot("Olinfo " + best + (waiting ? " · in corso" : ""), tooltip,
                        link.task(), latest == null ? null : latest.id()));
            } catch (SubmissionClient.LoginRequired exception) {
                publish(new Snapshot("Olinfo · Accedi", exception.getMessage(), null, null));
            } catch (Exception exception) {
                publish(new Snapshot("Olinfo · Errore", message(exception), null, null));
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void publish(Snapshot value) {
        if (disposed) return;
        snapshot = value;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) return;
            var bar = WindowManager.getInstance().getStatusBar(project);
            if (bar != null) bar.updateWidget(OlinfoScoreWidget.ID);
        });
    }

    void login() { promptLogin(session -> refresh(true)); }

    private void promptLogin(Consumer<String> continuation) {
        if (working) return;
        String username = Messages.showInputDialog(project, "Username o email di Training Olinfo", "Accedi a Training Olinfo", null);
        if (username == null || username.isBlank()) return;
        String password = Messages.showPasswordDialog(project, "Password di Training Olinfo", "Accedi a Training Olinfo", null);
        if (password == null || password.isEmpty()) return;
        run("Accesso a Training Olinfo", () -> {
            String session = api.login(username.strip(), password);
            PasswordSafe.getInstance().setPassword(SESSION, session);
            return session;
        }, session -> {
            pending = null;
            continuation.accept(session);
        });
    }

    void logout() {
        run("Uscita da Olinfo", () -> {
            PasswordSafe.getInstance().setPassword(SESSION, null);
            return null;
        }, ignored -> {
            pending = null;
            refresh(true);
        });
    }

    void submit() {
        if (working) return;
        ProjectLink link;
        try {
            link = projectLink();
            if (link == null) throw new IOException("Apri un progetto Olinfo attendibile con olinfo.xml.");
            if (link.source() == null) throw new IOException("Questo problema richiede un invio di soli output dal sito.");
        } catch (IOException exception) {
            showError(exception);
            return;
        }
        run("Controllo sessione Olinfo", () -> PasswordSafe.getInstance().getPassword(SESSION), session -> {
            if (session == null) promptLogin(value -> confirmAndSend(link, value));
            else confirmAndSend(link, session);
        });
    }

    private void confirmAndSend(ProjectLink link, String session) {
        if (Messages.showYesNoDialog(project, "Inviare " + link.source() + " a Training Olinfo per " + link.task() + "?",
                "Invia soluzione Olinfo", "Invia", "Annulla", Messages.getQuestionIcon()) != Messages.YES) return;
        var file = LocalFileSystem.getInstance().findFileByNioFile(link.root().resolve(link.source()));
        if (file != null) {
            var manager = FileDocumentManager.getInstance();
            var document = manager.getCachedDocument(file);
            if (document != null) {
                manager.saveDocument(document);
                if (manager.isDocumentUnsaved(document)) {
                    showError(new IOException("Salva il sorgente prima dell’invio."));
                    return;
                }
            }
        }
        run("Invio della soluzione a Olinfo", () -> api.submit(link, link.readSource(), session), id -> {
            pending = new Pending(link.task(), id, session, System.currentTimeMillis() + 120_000);
            refresh(true);
        });
    }

    void menu(MouseEvent event) {
        if (disposed || event == null) return;
        record Action(String name, Runnable run) { @Override public String toString() { return name; } }
        var actions = new java.util.ArrayList<>(List.of(
                new Action("Invia soluzione", this::submit),
                new Action("Aggiorna punteggio", () -> refresh(true)),
                new Action("Accedi a Training Olinfo", this::login),
                new Action("Esci dal plugin", this::logout)));
        try {
            ProjectLink link = projectLink();
            if (link != null) {
                String url = TrainingClient.ORIGIN + SubmissionClient.pathFor(link.task(), "submissions");
                actions.add(new Action("Apri gli invii sul sito", () -> BrowserUtil.browse(url)));
                Snapshot current = snapshot;
                if (link.task().equals(current.task()) && current.newestId() != null) {
                    actions.add(new Action("Apri l’invio #" + current.newestId(), () -> BrowserUtil.browse(url + "/" + current.newestId())));
                }
            }
        } catch (IOException ignored) {
            // The status tooltip shows the manifest error.
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(actions)
                .setTitle("Training Olinfo").setItemChosenCallback(action -> action.run().run())
                .createPopup().show(new RelativePoint(event));
    }

    private <T> void run(String title, Callable<T> operation, Consumer<T> success) {
        if (disposed || working) return;
        working = true;
        new Task.Backgroundable(project, title, false) {
            private T result;
            private Exception error;

            @Override public void run(@NotNull ProgressIndicator indicator) {
                try { result = operation.call(); } catch (Exception exception) { error = exception; }
            }

            @Override public void onSuccess() {
                working = false;
                if (disposed) return;
                if (error == null) success.accept(result);
                else {
                    if (error instanceof SubmissionClient.LoginRequired) refresh(true);
                    showError(error);
                }
            }
        }.queue();
    }

    private void showError(Exception exception) {
        Messages.showErrorDialog(project, message(exception), "Olinfo Trainer");
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? "Operazione non riuscita." : exception.getMessage();
    }

    @Override public void dispose() {
        disposed = true;
        timer.cancel(false);
    }
}
