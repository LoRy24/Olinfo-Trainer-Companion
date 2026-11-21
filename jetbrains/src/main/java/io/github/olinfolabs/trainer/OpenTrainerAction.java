package io.github.olinfolabs.trainer;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class OpenTrainerAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) return;
        var window = ToolWindowManager.getInstance(project).getToolWindow("Olinfo Trainer");
        if (window != null) window.activate(() -> {
            var manager = window.getContentManager();
            var create = manager.findContent(OlinfoToolWindow.NEW_PROBLEM);
            if (create != null) manager.setSelectedContent(create);
        });
    }
}
