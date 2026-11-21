package io.github.olinfolabs.trainer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.ui.RowIcon;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.Cursor;
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
        private final JBLabel badge = new JBLabel();

        Score(SubmissionUi ui) {
            badge.setBorder(JBUI.Borders.empty(0, 6));
            badge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            badge.getAccessibleContext().setAccessibleName("Account e punteggio Olinfo");
            badge.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(event)) ui.menu(event);
                }
            });
            ui.listen(this, () -> {
                var state = ui.snapshot();
                badge.setText(state.text());
                badge.setToolTipText(state.tooltip());
                var account = ui.account();
                var avatar = account == null ? null : OlinfoDashboard.avatar(account.photo(), 16);
                var dot = OlinfoDashboard.dot(state.color());
                if (avatar != null && dot != null) {
                    var icons = new RowIcon(2);
                    icons.setIcon(dot, 0);
                    icons.setIcon(avatar, 1);
                    badge.setIcon(icons);
                } else {
                    badge.setIcon(avatar == null ? dot : avatar);
                }
                badge.setVisible(!state.text().isEmpty());
            });
        }

        @Override public @NotNull String ID() { return ID; }
        @Override public @NotNull JComponent getComponent() { return badge; }
    }
}
