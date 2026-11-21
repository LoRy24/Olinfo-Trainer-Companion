package io.github.olinfolabs.trainer;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class OlinfoToolWindow implements ToolWindowFactory, DumbAware {
    static final String NEW_PROBLEM = "Nuovo problema";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var panel = new TrainerPanel(project);
        var content = ContentFactory.getInstance().createContent(panel.component(), NEW_PROBLEM, false);
        content.setDisposer(panel);
        window.getContentManager().addContent(content);
        var ui = project.getService(SubmissionUi.class);
        var current = new OlinfoDashboard.ProblemPanel(ui);
        var currentContent = ContentFactory.getInstance().createContent(current, "Questo problema", false);
        var lifetime = Disposer.newDisposable("Olinfo Current Problem");
        currentContent.setDisposer(lifetime);
        ui.listen(lifetime, current::render);
        window.getContentManager().addContent(currentContent);
        ui.refresh(true);
        panel.searchFirstPage();
    }
}
