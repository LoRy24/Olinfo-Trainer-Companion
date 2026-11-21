package io.github.olinfolabs.trainer;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class OlinfoToolWindow implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var panel = new TrainerPanel(project);
        var content = ContentFactory.getInstance().createContent(panel.component(), "Nuovo problema", false);
        content.setDisposer(panel);
        window.getContentManager().addContent(content);
        panel.searchFirstPage();
    }
}
