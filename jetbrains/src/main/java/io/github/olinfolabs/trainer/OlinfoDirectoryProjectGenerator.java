package io.github.olinfolabs.trainer;

import com.intellij.facet.ui.ValidationResult;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep;
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator;
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.DirectoryProjectGenerator;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class OlinfoDirectoryProjectGenerator
        implements DirectoryProjectGenerator<Void>, CustomStepProjectGenerator<Void> {
    static final String NAME = "Olinfo Trainer";
    static final Icon ICON = IconLoader.getIcon("/icons/toolWindow.svg", OlinfoDirectoryProjectGenerator.class);

    @Override public @NotNull String getName() { return NAME; }
    @Override public @NotNull Icon getLogo() { return ICON; }
    @Override public @NotNull ValidationResult validate(@NotNull String path) { return ValidationResult.OK; }

    @Override
    public void generateProject(@NotNull Project project, @NotNull VirtualFile directory, Void settings, @NotNull Module module) {
        // The custom step creates the project before opening it.
    }

    @Override
    public @NotNull ProjectSettingsStepBase<Void> createStep(DirectoryProjectGenerator<Void> generator,
                                                              AbstractNewProjectStep.AbstractCallback<Void> callback) {
        return new ProjectSettingsStepBase<>(generator, callback) {
            @Override
            public @NotNull JPanel createPanel() {
                var body = new JPanel(new BorderLayout());
                var trainer = new TrainerPanel(null, initialDirectory(), target -> openProject(body, target));
                Disposer.register(this, trainer);
                myCreateButton = trainer.createButton();
                body.add(trainer.component());
                trainer.searchFirstPage();
                return body;
            }
        };
    }

    static String initialDirectory() {
        String recent = RecentProjectsManager.getInstance().getLastProjectCreationLocation();
        if (recent != null) {
            try {
                if (Files.isDirectory(Path.of(recent))) return recent;
            } catch (InvalidPathException ignored) {
                // Fall back to the home directory if the saved path is invalid.
            }
        }
        return System.getProperty("user.home");
    }

    private static void openProject(JPanel panel, Path target) {
        var dialog = DialogWrapper.findInstance(panel);
        if (dialog != null) dialog.close(DialogWrapper.OK_EXIT_CODE);
        RecentProjectsManager.getInstance().setLastProjectCreationLocation(target.getParent());
        ApplicationManager.getApplication().invokeLater(
                () -> ProjectUtil.openOrImport(target, null, true), ModalityState.nonModal());
    }
}
