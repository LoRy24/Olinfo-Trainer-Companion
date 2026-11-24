package io.github.olinfolabs.trainer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class OlinfoScoreWidget implements StatusBarWidgetFactory {
    static final String ID = "OlinfoTrainer.Score";

    @Override public @NotNull String getId() { return ID; }
    @Override public @NotNull String getDisplayName() { return "Punteggio Olinfo"; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new Score(project.getService(SubmissionUi.class));
    }

    private static final class Score implements CustomStatusBarWidget {
        private final JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0));
        private final JBLabel badge = new JBLabel();
        private final JBLabel grade = new JBLabel();
        private final JBLabel spinner = new JBLabel(new AnimatedIcon.Default());
        private final JButton upload = new JButton(AllIcons.Actions.Upload);

        Score(SubmissionUi ui) {
            content.setOpaque(false);
            content.setBorder(JBUI.Borders.empty(0, 2));
            badge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            badge.getAccessibleContext().setAccessibleName("Account e punteggio Olinfo");
            badge.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(event)) ui.menu(badge);
                }
            });
            grade.setBorder(JBUI.Borders.emptyRight(2));
            spinner.setBorder(JBUI.Borders.empty(2, 3));
            upload.setBorder(JBUI.Borders.empty());
            upload.setPreferredSize(JBUI.size(23, 20));
            upload.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            upload.setToolTipText("Invia soluzione a Training Olinfo");
            upload.getAccessibleContext().setAccessibleName(upload.getToolTipText());
            upload.addActionListener(event -> ui.submit());
            content.add(badge);
            content.add(grade);
            content.add(upload);
            content.add(spinner);
            ui.listen(this, () -> {
                var state = ui.snapshot();
                badge.setText(state.text());
                badge.setToolTipText(state.tooltip());
                var account = ui.account();
                badge.setIcon(account == null ? null : OlinfoDashboard.avatar(account.photo(), 16));
                var dot = OlinfoDashboard.dot(state.color());
                grade.setIcon(dot);
                grade.setVisible(dot != null);
                grade.setToolTipText(state.tooltip());
                var problem = ui.problem();
                boolean hasSource = problem != null && problem.link().source() != null;
                boolean busy = ui.submitting() || ui.pendingId() != null || (hasSource && problem.waiting());
                upload.setToolTipText(hasSource && ProjectLink.isTerry(problem.link().task())
                        ? "Esegui e invia a Training Olinfo" : "Invia soluzione a Training Olinfo");
                upload.getAccessibleContext().setAccessibleName(upload.getToolTipText());
                upload.setVisible(hasSource && !busy);
                spinner.setVisible(hasSource && busy);
                spinner.setToolTipText(ui.submitting() ? "Invio in corso" : "Valutazione in corso su Training Olinfo");
                content.setVisible(!state.text().isEmpty());
            });
        }

        @Override public @NotNull String ID() { return ID; }
        @Override public @NotNull JComponent getComponent() { return content; }
    }
}
