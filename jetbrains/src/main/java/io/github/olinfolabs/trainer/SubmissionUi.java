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
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class SubmissionUi implements Disposable {
    private static final CredentialAttributes SESSION = new CredentialAttributes("Olinfo Trainer Companion / training.olinfo.it");

    private record Pending(String task, String id, String session, long until) {}
    record Snapshot(String text, String tooltip, String color) {}
    record Problem(ProjectLink link, String title, String mode, SubmissionClient.Results results,
                   boolean waiting, boolean signedOut, String message) {}
    record Account(String user, AccountData.Profile profile, BufferedImage photo, Integer rank,
                   boolean ranking, boolean signedOut, String message) {}

    private final Project project;
    private final SubmissionClient api = new SubmissionClient();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final java.util.concurrent.ScheduledFuture<?> timer;
    private final AtomicBoolean loadingAccount = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = new Snapshot("", "", "");
    private volatile Problem problem;
    private volatile Account account;
    private volatile String accountSession;
    private volatile long rankUntil;
    private volatile long nextAccount;
    private volatile long accountGeneration;
    private volatile boolean accountVisible;
    private volatile Pending pending;
    private volatile long nextRefresh;
    private volatile boolean disposed;
    private boolean working;

    public SubmissionUi(Project project) {
        this.project = project;
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                () -> {
                    refresh(false);
                    if (accountVisible && System.currentTimeMillis() >= nextAccount) refreshAccount();
                }, 1, 5, TimeUnit.SECONDS);
    }

    Snapshot snapshot() { return snapshot; }
    Problem problem() { return problem; }
    Account account() { return account; }

    void listen(Disposable lifetime, Runnable listener) {
        listeners.add(listener);
        Disposer.register(lifetime, () -> listeners.remove(listener));
        listener.run();
    }

    private ProjectLink projectLink() throws IOException {
        String base = project.getBasePath();
        return base == null || !TrustedProjects.isTrusted(project) ? null : ProjectLink.read(Path.of(base));
    }

    void refresh(boolean immediate) {
        if (immediate) nextRefresh = 0;
        if (disposed || System.currentTimeMillis() < nextRefresh || !refreshing.compareAndSet(false, true)) return;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            ProjectLink link = null;
            String title = null;
            String mode = null;
            try {
                nextRefresh = System.currentTimeMillis() + 45_000;
                link = projectLink();
                if (link == null) {
                    problem = null;
                    publish(new Snapshot("", "", ""));
                    return;
                }
                Problem previous = problem;
                if (previous != null && previous.link().task().equals(link.task()) && previous.title() != null) {
                    title = previous.title();
                    mode = previous.mode();
                } else {
                    try {
                        var meta = TrainingClient.taskPage(api.page("/task/" + link.task(), null));
                        title = meta.title();
                        mode = meta.mode();
                    } catch (IOException ignored) {
                        // The task ID still identifies the project if metadata is unavailable.
                    }
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
                if (same && latest != null && !latest.pending()) {
                    pending = null;
                    if (accountVisible) refreshAccount();
                } else if (same && latest == null && !waiting) pending = null;
                String best = result.best() == null ? "—" : result.best() + " pt";
                String tooltip = link.task() + " · Miglior punteggio: " + best
                        + (latest == null ? "" : " · Ultimo invio #" + latest.id() + " · " + latest.outcome());
                String color = result.best() == null ? "" : result.rows().stream()
                        .filter(row -> result.best().equals(row.score()))
                        .findFirst().map(row -> AccountData.scoreColor(row.score(), row.color())).orElse("");
                problem = new Problem(link, title, mode, result, waiting, false, null);
                publish(new Snapshot("Olinfo " + best + (waiting ? " · in corso" : ""), tooltip, color));
            } catch (SubmissionClient.LoginRequired exception) {
                if (link != null) problem = new Problem(link, title, mode, null, false, true, null);
                publish(new Snapshot("Olinfo · Accedi", exception.getMessage(), ""));
            } catch (Exception exception) {
                problem = link == null ? null : new Problem(link, title, mode, null, false, false, message(exception));
                publish(new Snapshot("Olinfo · Errore", message(exception), ""));
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void publish(Snapshot value) {
        if (disposed) return;
        snapshot = value;
        notifyViews();
    }

    private void notifyViews() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) return;
            var bar = WindowManager.getInstance().getStatusBar(project);
            if (bar != null) bar.updateWidget(OlinfoScoreWidget.ID);
            for (Runnable listener : listeners) listener.run();
        });
    }

    private void refreshAccount() {
        if (disposed || !loadingAccount.compareAndSet(false, true)) return;
        long generation = accountGeneration;
        nextAccount = System.currentTimeMillis() + 25_000;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                String session = PasswordSafe.getInstance().getPassword(SESSION);
                if (session == null) throw new SubmissionClient.LoginRequired();
                Account previous = session.equals(accountSession) ? account : null;
                String username = previous == null ? null : previous.user();
                if (username == null) username = AccountData.username(api.page("/", session));
                if (username == null) throw new SubmissionClient.LoginRequired();
                var metrics = AccountData.profile(api.page("/user/" + username, null));
                boolean samePhoto = previous != null && previous.profile() != null
                        && java.util.Objects.equals(previous.profile().avatar(), metrics.avatar());
                var photo = samePhoto ? previous.photo() : AccountData.photo(metrics.avatar());
                boolean updateRank = previous == null || previous.profile() == null
                        || previous.profile().points() != metrics.points() || System.currentTimeMillis() >= rankUntil;
                if (generation != accountGeneration || disposed) return;
                accountSession = session;
                account = new Account(username, metrics, photo, updateRank ? null : previous.rank(),
                        updateRank && metrics.points() > 0, false, null);
                notifyViews();
                if (updateRank && metrics.points() > 0) {
                    Integer rank;
                    try {
                        rank = AccountData.rank(location -> api.page(location, null), username, metrics.points());
                    } catch (IOException ignored) {
                        rank = null;
                    }
                    if (generation != accountGeneration || disposed) return;
                    account = new Account(username, metrics, photo, rank, false, false, null);
                    rankUntil = System.currentTimeMillis() + (rank == null ? 60_000 : 180_000);
                }
            } catch (Exception exception) {
                if (generation == accountGeneration) {
                    account = new Account(null, null, null, null, false,
                            exception instanceof SubmissionClient.LoginRequired, message(exception));
                }
            } finally {
                loadingAccount.set(false);
                if (generation == accountGeneration && !disposed) notifyViews();
            }
        });
    }

    private void resetAccount() {
        ++accountGeneration;
        account = null;
        accountSession = null;
        rankUntil = 0;
        nextAccount = 0;
        if (accountVisible) refreshAccount();
        notifyViews();
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
            resetAccount();
            continuation.accept(session);
        });
    }

    void logout() {
        run("Uscita da Olinfo", () -> {
            PasswordSafe.getInstance().setPassword(SESSION, null);
            return null;
        }, ignored -> {
            pending = null;
            resetAccount();
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
        var panel = new OlinfoDashboard.AccountPanel(this);
        var popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
                .setTitle("Account Olinfo").setMovable(true).setRequestFocus(true).createPopup();
        listen(popup, panel::render);
        accountVisible = true;
        Disposer.register(popup, () -> accountVisible = false);
        var anchor = event.getComponent();
        var point = anchor.getLocationOnScreen();
        var size = panel.getPreferredSize();
        point.translate(anchor.getWidth() - size.width, -size.height - 36);
        popup.showInScreenCoordinates(anchor, point);
        refreshAccount();
    }

    void openProfile() {
        Account current = account;
        if (current != null && current.user() != null) BrowserUtil.browse(TrainingClient.ORIGIN + "/user/" + current.user());
    }

    void openTask(String suffix) {
        Problem current = problem;
        if (current != null) BrowserUtil.browse(TrainingClient.ORIGIN + "/task/" + current.link().task() + suffix);
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
