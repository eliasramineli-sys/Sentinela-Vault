package br.com.sentinela;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;

/** Interface local Swing. Não contém serviços de rede ou telemetria. */
public final class Main extends JFrame {
    private static final Color BG = new Color(12, 20, 34), PANEL = new Color(23, 35, 53);
    private static final Color TEXT = new Color(235, 242, 250), MUTED = new Color(165, 183, 205);
    private static final Color ACCENT = new Color(96, 221, 193);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withZone(ZoneId.systemDefault());
    private final VaultService service = new VaultService();
    private final LoginThrottle throttle = new LoginThrottle(System::nanoTime);
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = panel(cards);
    private final JPasswordField loginPassword = new JPasswordField();
    private final JTextField selectedFile = new JTextField();
    private final JLabel loginStatus = label("Escolha seu cofre ou crie um novo perfil.", MUTED, 13);
    private final JLabel ownerLabel = label("Seu cofre", TEXT, 25);
    private final JLabel sessionLabel = label("", ACCENT, 13);
    private final JLabel status = label("", MUTED, 13);
    private final JTextField search = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Título", "Categoria", "Identificador", "Atualizado em"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private List<VaultService.EntrySummary> rows = List.of();
    private VaultService.Session session;
    private Path loginPath;
    private boolean busy, closing;
    private final Timer sessionTimer;
    private final AWTEventListener activityListener;

    private Main() {
        super("Sentinela Vault • Cofre de informações pessoais");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        pages.add(loginPage(), "login"); pages.add(vaultPage(), "vault"); setContentPane(pages);
        setSize(1080, 740); setMinimumSize(new Dimension(900, 620)); setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                closing = true; lock("Sessão encerrada."); dispose();
            }
        });
        addWindowStateListener(event -> {
            if ((event.getNewState() & ICONIFIED) != 0 && session != null) lock("Cofre bloqueado ao minimizar.");
        });
        activityListener = event -> {
            if (session == null || !(event.getSource() instanceof Component component)) return;
            Window window = component instanceof Window w ? w : SwingUtilities.getWindowAncestor(component);
            while (window != null && window != this) window = window.getOwner();
            if (window == this && ((event instanceof KeyEvent k && k.getID() == KeyEvent.KEY_PRESSED)
                    || (event instanceof MouseEvent m && m.getID() == MouseEvent.MOUSE_PRESSED))) {
                try { session.touch(); } catch (VaultException e) { lock(e.getMessage()); }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(activityListener, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        sessionTimer = new Timer(1000, event -> {
            if (session != null) {
                if (session.expired()) lock("Cofre bloqueado após dois minutos sem atividade.");
                else sessionLabel.setText("Sessão aberta • bloqueio em " + session.secondsLeft() + "s sem atividade");
            }
        });
        sessionTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                sessionTimer.stop(); Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener);
            }
        });
    }

    private JPanel loginPage() {
        JPanel outer = panel(new GridBagLayout());
        JPanel card = panel(new GridLayout(0, 1, 0, 10)); card.setBackground(PANEL);
        card.setBorder(BorderFactory.createEmptyBorder(32, 35, 32, 35));
        card.setPreferredSize(new Dimension(550, 470));
        card.add(label("SENTINELA VAULT", ACCENT, 27));
        card.add(label("Suas informações em um cofre local.", TEXT, 18));
        card.add(label("Entre com sua senha mestre para desbloquear.", MUTED, 13));
        selectedFile.setEditable(false); selectedFile.setToolTipText("Arquivo .svault selecionado");
        card.add(selectedFile);
        card.add(button("Selecionar cofre / abrir backup", () -> {
            if (busy) return;
            Path chosen = choose(false, "Selecionar cofre", null);
            if (chosen != null) { loginPath = chosen; selectedFile.setText(chosen.getFileName().toString()); }
        }));
        JLabel passwordLabel = label("Senha mestre", TEXT, 13); passwordLabel.setLabelFor(loginPassword);
        card.add(passwordLabel); card.add(loginPassword);
        card.add(button("Entrar no cofre", this::unlock));
        card.add(button("Criar perfil e novo cofre", this::create));
        card.add(loginStatus);
        outer.add(card); return outer;
    }

    private JPanel vaultPage() {
        JPanel outer = panel(new BorderLayout(22, 22));
        outer.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JPanel sidebar = panel(new GridLayout(0, 1, 0, 12));
        sidebar.setPreferredSize(new Dimension(180, 430));
        sidebar.add(label("SENTINELA", ACCENT, 22));
        sidebar.add(label("Cofre pessoal", MUTED, 14));
        sidebar.add(button("Novo registro", () -> guarded(this::add)));
        sidebar.add(button("Revelar dados", () -> guarded(this::reveal)));
        sidebar.add(button("Editar registro", () -> guarded(this::edit)));
        sidebar.add(button("Excluir registro", () -> guarded(this::delete)));
        sidebar.add(button("Atividade", () -> guarded(this::activity)));
        sidebar.add(button("Backup protegido", () -> guarded(this::backup)));
        sidebar.add(button("Trocar senha", () -> guarded(this::changePassword)));
        sidebar.add(button("Bloquear agora", () -> lock("Cofre bloqueado manualmente.")));
        outer.add(sidebar, BorderLayout.WEST);
        JPanel center = panel(new BorderLayout(0, 16));
        JPanel header = panel(new GridLayout(0, 1, 0, 7));
        header.add(ownerLabel); header.add(sessionLabel);
        header.add(label("AES-256-GCM • Arquivo criptografado • Dados sensíveis ocultos na lista", MUTED, 13));
        JLabel find = label("Buscar por título ou categoria", TEXT, 13); find.setLabelFor(search);
        header.add(find); header.add(search); center.add(header, BorderLayout.NORTH);
        table.setRowHeight(39); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(sorter); table.setFillsViewportHeight(true);
        table.setBackground(PANEL); table.setForeground(TEXT); table.setGridColor(BG);
        table.setSelectionBackground(new Color(41, 84, 93)); table.setSelectionForeground(TEXT);
        table.getTableHeader().setReorderingAllowed(false);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, renderer);
        center.add(new JScrollPane(table), BorderLayout.CENTER); center.add(status, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(search.getText()), 0, 1)); }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        outer.add(center, BorderLayout.CENTER); return outer;
    }

    private void unlock() {
        if (busy) return;
        if (loginPath == null) { info("Selecione o arquivo do seu cofre."); return; }
        long wait = throttle.secondsLeft(loginPath);
        if (wait > 0) { info("Aguarde " + wait + " segundos antes de tentar novamente."); return; }
        Path chosen = loginPath;
        char[] password = loginPassword.getPassword(); loginPassword.setText("");
        authenticate(() -> service.open(chosen, password), chosen, password);
    }

    private void create() {
        if (busy) return;
        JTextField owner = new JTextField();
        JPasswordField password = new JPasswordField(), confirmation = new JPasswordField();
        JPanel form = form("Nome do perfil (1–60 caracteres)", owner,
                "Senha mestre (16–256 caracteres)", password, "Repita a senha mestre", confirmation);
        int choice = JOptionPane.showConfirmDialog(this, form, "Criar novo cofre",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] first = password.getPassword(), second = confirmation.getPassword();
        password.setText(""); confirmation.setText("");
        if (choice != JOptionPane.OK_OPTION) { Crypto.wipe(first); Crypto.wipe(second); return; }
        boolean same = Arrays.equals(first, second); Crypto.wipe(second);
        if (!same) { Crypto.wipe(first); info("As senhas não coincidem."); return; }
        try { Crypto.validateNewPassword(first); VaultData.text(owner.getText().strip(), 1, 60); }
        catch (VaultException | IllegalArgumentException e) { Crypto.wipe(first); info("Informe um perfil válido e uma frase-senha de 16 a 256 caracteres."); return; }
        info("Guarde sua frase-senha. Sem ela, não há recuperação do conteúdo do cofre. Faça backups criptografados.");
        Path destination = choose(true, "Criar arquivo de cofre", "meu-cofre.svault");
        if (destination == null) { Crypto.wipe(first); return; }
        String name = owner.getText().strip(); owner.setText("");
        authenticate(() -> service.create(destination, name, first), destination, first);
    }

    @FunctionalInterface private interface OpenOperation { VaultService.Session run() throws Exception; }
    private void authenticate(OpenOperation operation, Path chosen, char[] password) {
        busy = true; loginStatus.setText("Processando acesso…");
        new SwingWorker<VaultService.Session, Void>() {
            @Override protected VaultService.Session doInBackground() throws Exception {
                try { return operation.run(); } finally { Crypto.wipe(password); }
            }
            @Override protected void done() {
                busy = false;
                try {
                    VaultService.Session opened = get();
                    if (closing || (getExtendedState() & ICONIFIED) != 0) {
                        opened.close(); loginStatus.setText("Cofre bloqueado."); return;
                    }
                    session = opened; loginPath = chosen; selectedFile.setText(chosen.getFileName().toString());
                    throttle.success(chosen); ownerLabel.setText("Olá, " + session.owner());
                    refresh(); cards.show(pages, "vault"); sessionLabel.setText("Sessão aberta • bloqueio após 2 min de inatividade");
                } catch (Exception e) {
                    if (session != null) { session.close(); session = null; }
                    throttle.failure(chosen);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    loginStatus.setText("Acesso não concluído.");
                    if (!closing) info(cause instanceof VaultException ? cause.getMessage() : "Não foi possível concluir o acesso.");
                }
            }
        }.execute();
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private void guarded(Action action) {
        if (session == null) return;
        try { session.touch(); action.run(); }
        catch (Exception e) {
            if (session != null && !session.isOpen()) lock("Sessão bloqueada.");
            info(e instanceof VaultException ? e.getMessage() : "A operação não pôde ser concluída.");
        }
    }

    private void refresh() throws VaultException {
        rows = session.entries(); model.setRowCount(0);
        for (var row : rows) model.addRow(new Object[]{row.title(), row.category(), mask(row.account()), DATE.format(Instant.ofEpochMilli(row.updatedAt()))});
        status.setText(rows.size() + " registro(s) • Alterações salvas no arquivo criptografado");
    }

    private UUID selected() throws VaultException {
        int row = table.getSelectedRow();
        if (row < 0) throw new VaultException("Selecione um registro da lista.");
        return rows.get(table.convertRowIndexToModel(row)).id();
    }

    private char[] confirmPassword() {
        JPasswordField field = new JPasswordField();
        int option = JOptionPane.showConfirmDialog(this, form("Confirme sua senha mestre", field),
                "Confirmar acesso", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] password = field.getPassword(); field.setText("");
        if (option != JOptionPane.OK_OPTION) { Crypto.wipe(password); return null; }
        return password;
    }

    private record Draft(String title, String category, String account, String secret, String notes) {}

    private Draft editor(VaultData.Entry original) {
        JTextField title = new JTextField(original == null ? "" : original.title());
        JComboBox<String> category = new JComboBox<>(new String[]{"Documento", "Credencial", "Contato", "Anotação", "Outro"});
        category.setEditable(true); if (original != null) category.setSelectedItem(original.category());
        JTextField account = new JTextField(original == null ? "" : original.account());
        JPasswordField secret = new JPasswordField(original == null ? "" : original.secret());
        JTextArea notes = new JTextArea(original == null ? "" : original.notes(), 4, 32);
        notes.setLineWrap(true); notes.setWrapStyleWord(true);
        JPanel fields = form("Título (até 120 caracteres)", title, "Categoria (até 40)", category,
                "Identificador / usuário (até 160)", account, "Informação protegida (até 4.096)", secret,
                "Anotações privadas (até 8.192)", new JScrollPane(notes));
        JButton generate = button("Gerar senha aleatória (28 caracteres)", () -> {
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*+-_";
            SecureRandom random = new SecureRandom(); char[] chars = new char[28];
            for (int i = 0; i < chars.length; i++) chars[i] = alphabet.charAt(random.nextInt(alphabet.length()));
            secret.setText(new String(chars)); Crypto.wipe(chars);
        });
        fields.add(generate);
        int option = JOptionPane.showConfirmDialog(this, fields, original == null ? "Novo registro" : "Editar registro",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] chars = secret.getPassword();
        try {
            if (option != JOptionPane.OK_OPTION || session == null) return null;
            return new Draft(title.getText(), String.valueOf(category.getSelectedItem()), account.getText(), new String(chars), notes.getText());
        } finally { Crypto.wipe(chars); secret.setText(""); notes.setText(""); account.setText(""); title.setText(""); }
    }

    private void add() throws Exception {
        Draft draft = editor(null); if (draft == null || session == null) return;
        session.add(draft.title(), draft.category(), draft.account(), draft.secret(), draft.notes()); refresh();
    }

    private void reveal() throws Exception {
        UUID id = selected(); char[] password = confirmPassword(); if (password == null || session == null) { Crypto.wipe(password); return; }
        VaultData.Entry entry = session.reveal(id, password);
        JTextArea text = new JTextArea("Identificador: " + entry.account() + "\n\nInformação protegida:\n" + entry.secret()
                + "\n\nAnotações:\n" + entry.notes(), 12, 50);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        JDialog dialog = new JDialog(this, "Dados revelados • fechamento em 30 segundos", true);
        dialog.add(new JScrollPane(text)); dialog.pack(); dialog.setLocationRelativeTo(this);
        Timer hide = new Timer(30_000, event -> dialog.dispose()); hide.setRepeats(false);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { hide.stop(); text.setText(""); }
        });
        dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE); hide.start(); dialog.setVisible(true);
        hide.stop(); text.setText(""); dialog.dispose();
    }

    private void edit() throws Exception {
        UUID id = selected(); char[] password = confirmPassword(); if (password == null || session == null) { Crypto.wipe(password); return; }
        VaultData.Entry entry = session.reveal(id, password);
        Draft draft = editor(entry); if (draft == null || session == null) return;
        password = confirmPassword(); if (password == null || session == null) { Crypto.wipe(password); return; }
        session.update(id, draft.title(), draft.category(), draft.account(), draft.secret(), draft.notes(), password); refresh();
    }

    private void delete() throws Exception {
        UUID id = selected();
        if (JOptionPane.showConfirmDialog(this, "Excluir este registro do cofre atual? Backups anteriores não serão alterados.",
                "Confirmar exclusão", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        char[] password = confirmPassword(); if (password == null || session == null) { Crypto.wipe(password); return; }
        session.delete(id, password); refresh();
    }

    private void backup() throws Exception {
        Path target = choose(true, "Salvar backup criptografado", "backup-cofre.svault");
        if (target == null || session == null) return;
        char[] password = confirmPassword(); if (password == null || session == null) { Crypto.wipe(password); return; }
        session.backup(target, password); status.setText("Backup criptografado criado. Para restaurar, abra essa cópia na tela de acesso.");
    }

    private void changePassword() throws Exception {
        JPasswordField current = new JPasswordField(), replacement = new JPasswordField(), confirmation = new JPasswordField();
        int option = JOptionPane.showConfirmDialog(this, form("Senha atual", current, "Nova senha (16–256 caracteres)", replacement,
                "Repita a nova senha", confirmation), "Trocar senha mestre", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] old = current.getPassword(), next = replacement.getPassword(), repeated = confirmation.getPassword();
        current.setText(""); replacement.setText(""); confirmation.setText("");
        try {
            if (option != JOptionPane.OK_OPTION || session == null) return;
            if (!Arrays.equals(next, repeated)) throw new VaultException("As novas senhas não coincidem.");
            session.changePassword(old, next);
            info("Senha alterada para o cofre atual. Backups antigos continuam usando a senha anterior.");
        } finally { Crypto.wipe(old); Crypto.wipe(next); Crypto.wipe(repeated); }
    }

    private void activity() throws Exception {
        StringBuilder text = new StringBuilder("Atividade de alterações • até 100 eventos\n\n");
        for (VaultData.Event event : session.activity()) text.append(DATE.format(Instant.ofEpochMilli(event.timestamp())))
                .append(" • ").append(event.action().replace('_', ' ')).append('\n');
        JTextArea area = new JTextArea(text.toString(), 14, 48); area.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Atividade do cofre", JOptionPane.PLAIN_MESSAGE);
        area.setText("");
    }

    private void lock(String message) {
        for (Window owned : getOwnedWindows()) owned.dispose();
        if (session != null) { session.close(); session = null; }
        rows = List.of(); model.setRowCount(0); search.setText(""); ownerLabel.setText("Seu cofre");
        loginPassword.setText(""); loginStatus.setText(message); cards.show(pages, "login");
    }

    private Path choose(boolean save, String title, String suggested) {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("Cofre Sentinela (*.svault)", "svault"));
        if (suggested != null) chooser.setSelectedFile(new java.io.File(suggested));
        int option = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (option != JFileChooser.APPROVE_OPTION) return null;
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (save && !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".svault"))
            path = path.resolveSibling(path.getFileName() + ".svault");
        return path;
    }

    private void info(String message) { JOptionPane.showMessageDialog(this, message, "Sentinela Vault", JOptionPane.INFORMATION_MESSAGE); }
    private static String mask(String value) { return value.isEmpty() ? "—" : "••••" + (value.length() > 4 ? value.substring(value.length() - 4) : ""); }
    private static JPanel panel(LayoutManager layout) { JPanel p = new JPanel(layout); p.setBackground(BG); return p; }
    private static JLabel label(String text, Color color, int size) {
        JLabel label = new JLabel(text); label.putClientProperty("html.disable", Boolean.TRUE);
        label.setForeground(color); label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size)); return label;
    }
    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text); button.setFocusPainted(true); button.addActionListener(event -> action.run()); return button;
    }
    private static JPanel form(Object... fields) {
        JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (int i = 0; i < fields.length; i += 2) {
            JLabel label = new JLabel(fields[i].toString()); JComponent field = (JComponent) fields[i + 1];
            label.setLabelFor(field); label.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            int height = field instanceof JScrollPane ? 85 : 30;
            field.setPreferredSize(new Dimension(470, height));
            field.setMaximumSize(new Dimension(640, height));
            form.add(label); form.add(Box.createVerticalStrut(4)); form.add(field); form.add(Box.createVerticalStrut(9));
        }
        return form;
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A interface precisa de um ambiente gráfico. Para executar testes: java Build.java test"); return;
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
            catch (Exception ignored) { }
            UIManager.put("Panel.background", BG); UIManager.put("OptionPane.background", BG);
            UIManager.put("OptionPane.messageForeground", TEXT); UIManager.put("Label.foreground", TEXT);
            UIManager.put("TextField.background", PANEL); UIManager.put("TextField.foreground", TEXT);
            UIManager.put("PasswordField.background", PANEL); UIManager.put("PasswordField.foreground", TEXT);
            UIManager.put("TextArea.background", PANEL); UIManager.put("TextArea.foreground", TEXT);
            UIManager.put("TextField.caretForeground", ACCENT); UIManager.put("PasswordField.caretForeground", ACCENT);
            UIManager.put("Button.background", new Color(42, 73, 85)); UIManager.put("Button.foreground", TEXT);
            UIManager.put("Button.font", new Font(Font.SANS_SERIF, Font.BOLD, 12));
            new Main().setVisible(true);
        });
    }
}
