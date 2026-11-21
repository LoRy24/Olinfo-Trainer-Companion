package io.github.olinfolabs.trainer;

import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.ide.wizard.AbstractNewProjectWizardStep;
import com.intellij.ide.wizard.GeneratorNewProjectWizard;
import com.intellij.ide.wizard.NewProjectWizardBaseStep;
import com.intellij.ide.wizard.NewProjectWizardChainStep;
import com.intellij.ide.wizard.NewProjectWizardStep;
import com.intellij.ide.wizard.RootNewProjectWizardStep;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.dsl.builder.Align;
import com.intellij.ui.dsl.builder.Panel;
import com.intellij.ui.layout.ValidationInfoBuilder;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class OlinfoIdeaProjectWizard implements GeneratorNewProjectWizard {
    @Override public @NotNull String getId() { return "OlinfoTrainer"; }
    @Override public @NotNull String getName() { return OlinfoDirectoryProjectGenerator.NAME; }
    @Override public @NotNull Icon getIcon() { return OlinfoDirectoryProjectGenerator.ICON; }

    @Override
    public @NotNull NewProjectWizardStep createStep(@NotNull WizardContext context) {
        return new NewProjectWizardChainStep<>(new RootNewProjectWizardStep(context))
                .nextStep(root -> {
                    var base = new NewProjectWizardBaseStep(root);
                    base.setPath(OlinfoDirectoryProjectGenerator.initialDirectory());
                    return base;
                })
                .nextStep(Step::new);
    }

    private static final class Step extends AbstractNewProjectWizardStep {
        private final NewProjectWizardBaseStep location;
        private ProjectCreator.Prepared prepared;

        Step(NewProjectWizardBaseStep location) {
            super(location);
            this.location = location;
        }

        @Override
        public void setupUI(@NotNull Panel panel) {
            var trainer = TrainerPanel.catalog(getContext().getProject());
            Disposer.register(getContext().getDisposable(), trainer);
            panel.row((String) null, row -> {
                row.cell(trainer.component()).align(Align.FILL)
                        .validationOnApply((validator, component) -> prepare(validator, trainer.selection()));
                return Unit.INSTANCE;
            }).resizableRow();
            trainer.searchFirstPage();
        }

        private ValidationInfo prepare(ValidationInfoBuilder validator, TrainerPanel.ProblemSelection selection) {
            prepared = null;
            if (!getContext().isCreatingNewProject()) return validator.error("Apri New Project per creare un progetto Olinfo.");
            if (selection == null) return validator.error("Seleziona un problema, la lingua del PDF e il linguaggio.");

            String parent = location.getPath();
            String name = location.getName();
            try {
                prepared = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        () -> ProjectCreator.prepare(new TrainingClient(), selection.details(), selection.language(),
                                selection.locale(), parent, name),
                        "Scaricamento file Olinfo", true, getContext().getProject());
                return null;
            } catch (ProcessCanceledException exception) {
                return validator.error("Scaricamento annullato.");
            } catch (IOException exception) {
                return validator.error(exception.getMessage());
            }
        }

        @Override
        public void setupProject(@NotNull Project project) {
            if (prepared == null) throw new IllegalStateException("Seleziona un problema Olinfo prima di creare il progetto.");
            String base = project.getBasePath();
            if (base == null || !prepared.target().normalize().equals(Path.of(base).normalize())) {
                throw new IllegalStateException("La cartella del progetto è diversa dalla destinazione scelta.");
            }
            try {
                prepared.populate();
            } catch (IOException exception) {
                throw new UncheckedIOException("Impossibile salvare i file Olinfo.", exception);
            }
        }
    }
}
