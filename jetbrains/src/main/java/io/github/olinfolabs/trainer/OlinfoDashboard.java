package io.github.olinfolabs.trainer;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

final class OlinfoDashboard {
    private OlinfoDashboard() {}

    private static String number(int value) { return NumberFormat.getIntegerInstance(Locale.ITALIAN).format(value); }

    private static JButton button(String label, Runnable action) {
        var button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }

    static Icon dot(String grade) {
        Color color = AccountData.color(grade);
        return color == null ? null : new Circle(color, null, JBUI.scale(8));
    }

    private record Circle(Color color, BufferedImage picture, int size) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                var shape = new Ellipse2D.Double(x, y, size, size);
                g.setColor(color);
                g.fill(shape);
                if (picture != null) {
                    g.clip(shape);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(picture, x, y, size, size, null);
                }
            } finally {
                g.dispose();
            }
        }
    }

    static final class AccountPanel extends JPanel {
        private final SubmissionUi ui;
        private final JBLabel avatar = new JBLabel();
        private final JBLabel user = new JBLabel();
        private final JBLabel points = new JBLabel();
        private final JBLabel solved = new JBLabel();
        private final JBLabel position = new JBLabel();
        private final JBLabel message = new JBLabel();
        private final JPanel metrics = new JPanel();
        private final JButton login;
        private final JButton profile;
        private final JButton logout;

        AccountPanel(SubmissionUi ui) {
            this.ui = ui;
            setLayout(new BorderLayout(0, JBUI.scale(12)));
            setBorder(JBUI.Borders.empty(12));
            setPreferredSize(JBUI.size(340, 225));
            var body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            var identity = new JPanel(new BorderLayout(JBUI.scale(11), 0));
            user.setFont(user.getFont().deriveFont(Font.BOLD, user.getFont().getSize2D() + 3));
            identity.add(avatar, BorderLayout.WEST);
            identity.add(user, BorderLayout.CENTER);
            identity.setAlignmentX(LEFT_ALIGNMENT);
            body.add(identity);
            body.add(Box.createVerticalStrut(JBUI.scale(10)));
            metrics.setLayout(new BoxLayout(metrics, BoxLayout.Y_AXIS));
            metrics.setAlignmentX(LEFT_ALIGNMENT);
            addMetric("Punti totali", points);
            addMetric("Problemi risolti", solved);
            addMetric("Posizione in classifica", position);
            body.add(metrics);
            message.setAlignmentX(LEFT_ALIGNMENT);
            message.setForeground(JBColor.GRAY);
            body.add(message);
            add(body, BorderLayout.CENTER);

            var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            login = button("Accedi", ui::login);
            profile = button("Apri profilo", ui::openProfile);
            logout = button("Esci", ui::logout);
            actions.add(login);
            actions.add(profile);
            actions.add(logout);
            add(actions, BorderLayout.SOUTH);
        }

        private void addMetric(String label, JBLabel value) {
            var row = new JPanel(new BorderLayout(JBUI.scale(10), 0));
            row.setBorder(JBUI.Borders.empty(4, 1));
            value.setFont(value.getFont().deriveFont(Font.BOLD));
            row.add(new JBLabel(label), BorderLayout.CENTER);
            row.add(value, BorderLayout.EAST);
            metrics.add(row);
        }

        void render() {
            var account = ui.account();
            boolean signedIn = account != null && account.user() != null;
            metrics.setVisible(signedIn);
            profile.setVisible(signedIn);
            logout.setVisible(signedIn);
            login.setVisible(account != null && account.signedOut());
            message.setText(account == null ? "Caricamento account…" : account.message());
            if (signedIn) {
                user.setText("@" + account.user());
                if (account.photo() == null) {
                    avatar.setIcon(null);
                    avatar.setText(account.user().substring(0, 2).toUpperCase(Locale.ROOT));
                } else {
                    avatar.setText("");
                    avatar.setIcon(new Circle(JBColor.GRAY, account.photo(), JBUI.scale(42)));
                }
                points.setText(number(account.profile().points()));
                solved.setText(number(account.profile().solved()));
                position.setText(account.ranking() ? "…" : account.rank() == null ? "—" : "#" + number(account.rank()));
            } else {
                user.setText("");
                avatar.setText("");
                avatar.setIcon(null);
            }
        }
    }

    static final class ProblemPanel extends JPanel {
        private final SubmissionUi ui;
        private final JBLabel title = new JBLabel();
        private final JBLabel details = new JBLabel();
        private final JBLabel best = new JBLabel();
        private final JBLabel status = new JBLabel();
        private final DefaultListModel<SubmissionClient.Row> rows = new DefaultListModel<>();
        private final JBList<SubmissionClient.Row> submissions = new JBList<>(rows);
        private final JButton login;
        private final JButton submit;
        private final JButton open;
        private final JButton history;
        private final JButton showSubmission;
        private SubmissionUi.Problem rendered;

        ProblemPanel(SubmissionUi ui) {
            this.ui = ui;
            setLayout(new BorderLayout(0, JBUI.scale(8)));
            setBorder(JBUI.Borders.empty(12));
            var header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
            header.add(title);
            header.add(details);
            header.add(Box.createVerticalStrut(JBUI.scale(6)));
            header.add(best);
            header.add(Box.createVerticalStrut(JBUI.scale(8)));
            var actions = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(4)));
            actions.setAlignmentX(LEFT_ALIGNMENT);
            login = button("Accedi", ui::login);
            submit = button("Invia soluzione", ui::submit);
            open = button("Apri su Training", () -> ui.openTask(""));
            actions.add(submit);
            actions.add(open);
            actions.add(login);
            header.add(actions);
            header.add(Box.createVerticalStrut(JBUI.scale(10)));
            header.add(new JBLabel("Sottoposizioni"));
            header.add(status);
            add(header, BorderLayout.NORTH);

            submissions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            submissions.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                          boolean selected, boolean focus) {
                    var label = (javax.swing.JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof SubmissionClient.Row row) {
                        label.setText("#" + row.id() + "   " + (row.score() == null ? row.outcome() : row.score() + " pt"));
                        label.setIcon(dot(AccountData.scoreColor(row.score(), row.color())));
                        label.setBorder(JBUI.Borders.empty(5, 6));
                    }
                    return label;
                }
            });
            add(new JBScrollPane(submissions), BorderLayout.CENTER);

            var bottom = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(4)));
            history = button("Tutti gli invii", () -> ui.openTask("/submissions"));
            showSubmission = button("Apri invio", this::openSelected);
            bottom.add(button("Aggiorna", () -> ui.refresh(true)));
            bottom.add(showSubmission);
            bottom.add(history);
            add(bottom, BorderLayout.SOUTH);
            submissions.addListSelectionListener(event -> showSubmission.setEnabled(submissions.getSelectedValue() != null));
            submissions.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) openSelected();
                }
            });
        }

        private void openSelected() {
            var row = submissions.getSelectedValue();
            if (row != null) ui.openTask("/submissions/" + row.id());
        }

        void render() {
            var problem = ui.problem();
            if (problem == rendered && problem != null) return;
            var selected = submissions.getSelectedValue();
            boolean sameTask = rendered != null && problem != null && rendered.link().task().equals(problem.link().task());
            rendered = problem;
            rows.clear();
            title.setText(problem == null ? "Questo problema" : Objects.requireNonNullElse(problem.title(), problem.link().task()));
            details.setText(problem == null ? "Apri una cartella con olinfo.xml."
                    : problem.link().task() + " · " + problem.link().language()
                    + (problem.link().source() == null ? "" : " · " + problem.link().source())
                    + (problem.mode() == null || problem.mode().isBlank() ? "" : " · " + problem.mode()));
            var results = problem == null ? null : problem.results();
            best.setText(results == null ? "" : "Migliore · " + (results.best() == null ? "—" : number(results.best()) + " pt"));
            best.setIcon(results == null ? null : dot(ui.snapshot().color()));
            login.setVisible(problem != null && problem.signedOut());
            submit.setEnabled(problem != null && problem.link().source() != null);
            open.setEnabled(problem != null);
            history.setEnabled(problem != null);
            showSubmission.setEnabled(false);
            status.setText(problem == null ? "" : problem.message() != null ? problem.message()
                    : problem.waiting() ? "Valutazione in corso…" : problem.signedOut() ? "Accedi per vedere i tuoi invii."
                    : results != null && results.rows().isEmpty() ? "Nessun invio." : "");
            if (results != null) {
                rows.addAll(results.rows().stream().limit(20).toList());
                if (sameTask && selected != null) {
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).id().equals(selected.id())) submissions.setSelectedIndex(i);
                    }
                }
            }
        }
    }
}
