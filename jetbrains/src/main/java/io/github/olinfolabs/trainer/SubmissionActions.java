package io.github.olinfolabs.trainer;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class SubmissionActions {
    private SubmissionActions() {}

    public abstract static class Action extends DumbAwareAction {
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabled(event.getProject() != null);
        }
        @Override public void actionPerformed(@NotNull AnActionEvent event) {
            var project = event.getProject();
            if (project != null) execute(project.getService(SubmissionUi.class));
        }
        abstract void execute(SubmissionUi ui);
    }

    public static final class Login extends Action {
        @Override void execute(SubmissionUi ui) { ui.login(); }
    }
    public static final class Logout extends Action {
        @Override void execute(SubmissionUi ui) { ui.logout(); }
    }
    public static final class Submit extends Action {
        @Override void execute(SubmissionUi ui) { ui.submit(); }
    }
    public static final class Refresh extends Action {
        @Override void execute(SubmissionUi ui) { ui.refresh(true); }
    }
}
