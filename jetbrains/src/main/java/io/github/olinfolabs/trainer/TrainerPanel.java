package io.github.olinfolabs.trainer;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

final class TrainerPanel implements Disposable {
    private final Project project;
    private final Consumer<Path> onCreated;
    private final boolean ownsLocation;
    private final TrainingClient client = new TrainingClient();
    private final JBTextField query = new JBTextField();
    private final JButton search = new JButton("Cerca");
    private final DefaultListModel<TrainingClient.Task> results = new DefaultListModel<>();
    private final JBList<TrainingClient.Task> list = new JBList<>(results);
    private final JButton previous = new JButton("Indietro");
    private final JButton next = new JButton("Avanti");
    private final JBLabel pageStatus = new JBLabel();
    private final JBTextArea taskStatus = statusArea();
    private final JBTextArea graderStatus = statusArea();
    private final JBTextArea createStatus = statusArea();
    private final ComboBox<TrainingClient.Selection> locales = new ComboBox<>();
    private final ComboBox<TrainingClient.Selection> languages = new ComboBox<>();
    private final JBTextField name = new JBTextField();
    private final JBTextField parent = new JBTextField();
    private final JButton browse = new JButton("Sfoglia…");
    private final JButton create = new JButton("Crea progetto");
    private final JButton open = new JButton("Apri cartella");
    private final JComponent root;
    private TrainingClient.Details details;
    private Path generated;
    private String activeQuery = "";
    private String suggestedName = "";
    private int page = 1;
    private int searchId;
    private int detailsId;
    private boolean hasNext;
    private boolean searching;
    private boolean creating;
    private volatile boolean disposed;

    TrainerPanel(Project project) {
        this(project, project.getBasePath(), null);
    }

    TrainerPanel(Project project, String initialDirectory, Consumer<Path> onCreated) {
        this(project, initialDirectory, onCreated, true);
    }

    static TrainerPanel catalog(Project project) {
        return new TrainerPanel(project, "", null, false);
    }

    private TrainerPanel(Project project, String initialDirectory, Consumer<Path> onCreated, boolean ownsLocation) {
        this.project = project;
        this.onCreated = onCreated;
        this.ownsLocation = ownsLocation;
        parent.setText(initialDirectory);
        root = buildPanel();
        query.getEmptyText().setText("Titolo o identificativo");
        query.getAccessibleContext().setAccessibleName("Titolo o identificativo del problema");
        list.getAccessibleContext().setAccessibleName("Problemi trovati");
        query.addActionListener(event -> searchFirstPage());
        search.addActionListener(event -> searchFirstPage());
        previous.addActionListener(event -> search(activeQuery, page - 1));
        next.addActionListener(event -> search(activeQuery, page + 1));
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) selectTask(); });
        languages.addActionListener(event -> { updateGraderStatus(); updateControls(); });
        locales.addActionListener(event -> updateControls());
        browse.addActionListener(event -> browse());
        create.addActionListener(event -> create());
        open.addActionListener(event -> {
            if (generated != null) ProjectUtil.openOrImport(generated, project, true);
        });
        open.setVisible(false);
        taskStatus.setText("Seleziona un problema dal catalogo.");
        updateControls();
    }

    JComponent component() { return root; }
    JButton createButton() { return create; }

    record ProblemSelection(TrainingClient.Details details, String language, String locale) {}

    ProblemSelection selection() {
        var language = (TrainingClient.Selection) languages.getSelectedItem();
        var locale = (TrainingClient.Selection) locales.getSelectedItem();
        return details == null || language == null || locale == null
                ? null : new ProblemSelection(details, language.id(), locale.id());
    }

    private JComponent buildPanel() {
        var body = new ScrollableForm();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(JBUI.Borders.empty(12));
        var catalog = section("Catalogo");
        add(catalog, inline(query, search));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(7);
        list.setPrototypeCellValue(new TrainingClient.Task("id", "Problema Olinfo"));
        var resultPane = new JBScrollPane(list);
        resultPane.setPreferredSize(new Dimension(0, JBUI.scale(158)));
        resultPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(158)));
        add(catalog, resultPane);
        var paging = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        pageStatus.setHorizontalAlignment(JBLabel.CENTER);
        paging.add(previous, BorderLayout.WEST);
        paging.add(pageStatus, BorderLayout.CENTER);
        paging.add(next, BorderLayout.EAST);
        add(catalog, paging);

        var settings = section("Progetto");
        add(settings, taskStatus);
        var selections = new JPanel(new GridLayout(1, 2, JBUI.scale(8), 0));
        selections.add(field("Lingua del PDF", locales));
        selections.add(field("Linguaggio", languages));
        add(settings, selections);
        add(settings, graderStatus);
        if (ownsLocation) {
            add(settings, field("Nome progetto", name));
            var destination = field("Cartella di destinazione", inline(parent, browse));
            ((JBLabel) destination.getComponent(0)).setLabelFor(parent);
            add(settings, destination);
            var actions = new JPanel(new GridLayout(1, 2, JBUI.scale(8), 0));
            actions.add(create);
            actions.add(open);
            add(settings, actions);
            add(settings, createStatus);
        }

        catalog.setAlignmentX(0);
        settings.setAlignmentX(0);
        body.add(catalog);
        body.add(Box.createVerticalStrut(JBUI.scale(10)));
        body.add(settings);
        body.add(Box.createVerticalGlue());
        return new JBScrollPane(body, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    }

    private static final class ScrollableForm extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle area, int direction, int step) { return JBUI.scale(16); }
        @Override public int getScrollableBlockIncrement(Rectangle area, int direction, int step) { return area.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JPanel section(String title) {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(8)));
        var heading = new JBLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        add(panel, heading);
        return panel;
    }

    private static void add(JPanel panel, JComponent component) {
        component.setAlignmentX(0);
        panel.add(component);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
    }

    private static JPanel inline(JComponent center, JComponent trailing) {
        var panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        panel.add(center, BorderLayout.CENTER);
        panel.add(trailing, BorderLayout.EAST);
        return panel;
    }

    private static JPanel field(String caption, JComponent component) {
        var panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        var label = new JBLabel(caption);
        label.setLabelFor(component);
        panel.add(label, BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static JBTextArea statusArea() {
        var area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(new JBLabel().getFont());
        area.setColumns(1);
        area.setRows(1);
        return area;
    }

    void searchFirstPage() { search(query.getText(), 1); }

    private void search(String text, int requestedPage) {
        activeQuery = text;
        page = requestedPage;
        searching = true;
        hasNext = false;
        int request = ++searchId;
        results.clear();
        selectTask();
        pageStatus.setText("Ricerca in corso…");
        pageStatus.setToolTipText(null);
        updateControls();
        background(() -> client.search(text, requestedPage), result -> {
            if (request != searchId) return;
            searching = false;
            hasNext = result.hasNext();
            results.addAll(result.tasks());
            pageStatus.setText(results.isEmpty() ? "Nessun problema trovato" : "Pagina " + requestedPage);
            if (results.size() == 1) list.setSelectedIndex(0);
            updateControls();
        }, error -> {
            if (request != searchId) return;
            searching = false;
            pageStatus.setText("Ricerca non riuscita");
            pageStatus.setToolTipText(error);
            taskStatus.setText(error);
            updateControls();
        });
    }

    private void selectTask() {
        int request = ++detailsId;
        details = null;
        var task = list.getSelectedValue();
        locales.removeAllItems();
        languages.removeAllItems();
        updateGraderStatus();
        updateControls();
        if (task == null) {
            taskStatus.setText("Seleziona un problema dal catalogo.");
            return;
        }
        taskStatus.setText("Caricamento del problema…");
        background(() -> client.details(task.id()), result -> {
            if (request != detailsId) return;
            details = result;
            for (var item : result.pdf()) locales.addItem(item);
            for (var item : result.languages()) languages.addItem(item.choice());
            if (result.pdf().isEmpty()) taskStatus.setText("Il PDF non è disponibile.");
            else if (result.languages().isEmpty()) taskStatus.setText("Nessun sorgente o grader pubblico disponibile.");
            else taskStatus.setText(result.title() + " · " + result.id());
            if (name.getText().isBlank() || name.getText().equals(suggestedName)) {
                name.setText(result.id());
                suggestedName = result.id();
            }
            updateGraderStatus();
            updateControls();
        }, error -> { if (request == detailsId) taskStatus.setText(error); });
    }

    private void updateGraderStatus() {
        var language = (TrainingClient.Selection) languages.getSelectedItem();
        var notes = new ArrayList<String>();
        if (details != null) {
            if (language != null && details.mode().equals("grader")) {
                for (var option : details.languages()) {
                    if (!option.choice().equals(language)) continue;
                    if (option.grader() == null) notes.add("Training Olinfo non pubblica il grader per questo linguaggio.");
                    if (option.starter() == null) notes.add("La firma da implementare è indicata nel PDF.");
                }
            }
            notes.addAll(details.notes());
        }
        graderStatus.setText(String.join(" ", notes));
        graderStatus.setVisible(!notes.isEmpty());
    }

    private void browse() {
        var initial = LocalFileSystem.getInstance().findFileByPath(parent.getText());
        var folder = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, initial);
        if (folder != null) parent.setText(folder.getPath());
    }

    private void create() {
        var selected = selection();
        if (creating || selected == null) return;
        String destination = parent.getText();
        String projectName = name.getText();
        creating = true;
        open.setVisible(false);
        createStatus.setText("Download e creazione in corso…");
        updateControls();
        background(() -> {
            var projectFiles = ProjectCreator.prepare(client, selected.details(), selected.language(),
                    selected.locale(), destination, projectName);
            return disposed ? null : projectFiles.create();
        }, result -> {
            generated = result.target();
            creating = false;
            open.setVisible(onCreated == null);
            createStatus.setText("Progetto creato in " + generated);
            updateControls();
            if (onCreated != null) onCreated.accept(generated);
        }, error -> {
            creating = false;
            createStatus.setText(error);
            updateControls();
        });
    }

    private void updateControls() {
        query.setEnabled(!creating);
        search.setEnabled(!creating);
        previous.setEnabled(!creating && !searching && page > 1);
        next.setEnabled(!creating && !searching && hasNext);
        list.setEnabled(!creating && !searching && !results.isEmpty());
        languages.setEnabled(!creating && languages.getItemCount() > 0);
        locales.setEnabled(!creating && locales.getItemCount() > 0);
        name.setEnabled(!creating);
        parent.setEnabled(!creating);
        browse.setEnabled(!creating);
        create.setEnabled(!creating && details != null && locales.getSelectedItem() != null && languages.getSelectedItem() != null);
    }

    private <T> void background(Callable<T> operation, Consumer<T> success, Consumer<String> failure) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                T result = operation.call();
                SwingUtilities.invokeLater(() -> { if (!disposed) success.accept(result); });
            } catch (Exception exception) {
                String message = exception.getMessage();
                SwingUtilities.invokeLater(() -> {
                    if (!disposed) failure.accept(message == null ? "Operazione non riuscita." : message);
                });
            }
        });
    }

    @Override public void dispose() { disposed = true; }
}
