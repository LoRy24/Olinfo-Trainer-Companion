package io.github.olinfolabs.trainer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

public final class OlinfoScoreWidget implements StatusBarWidgetFactory {
    static final String ID = "OlinfoTrainer.Score";

    @Override public @NotNull String getId() { return ID; }
    @Override public @NotNull String getDisplayName() { return "Punteggio Olinfo"; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new Score(project.getService(SubmissionUi.class));
    }

    private record Score(SubmissionUi ui) implements StatusBarWidget, StatusBarWidget.TextPresentation {
        @Override public @NotNull String ID() { return ID; }
        @Override public @NotNull WidgetPresentation getPresentation() { return this; }
        @Override public @NotNull String getText() { return ui.snapshot().text(); }
        @Override public String getTooltipText() { return ui.snapshot().tooltip(); }
        @Override public float getAlignment() { return 0.5f; }
        @Override public Consumer<MouseEvent> getClickConsumer() { return ui::menu; }
    }
}
