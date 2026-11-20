package ru.fr1bilder.contractdesk;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public final class App extends JFrame {
  private final Store store;
  private final DefaultTableModel contractsModel =
      model("№", "Договор", "Клиент", "Сумма, ₽", "Оплачено, ₽", "Остаток, ₽", "Срок", "Статус");
  private final DefaultTableModel clientsModel = model("№", "Клиент", "Контакт", "Заметки");
  private final JTable contractsTable = table(contractsModel), clientsTable = table(clientsModel);
  private final JTextField search = new JTextField(24);
  private final JComboBox<String> filter =
      new JComboBox<>(new String[] {"Все статусы", "В работе", "Просрочен", "Завершён", "Отменён"});
  private final JLabel summary = new JLabel(), footer = new JLabel();
  private final JTabbedPane tabs = new JTabbedPane();
  private List<Contract> contracts = List.of();
  private List<Client> clients = List.of();
  private boolean reminderShown;
  private static final Color INK = new Color(29, 42, 57),
      MUTED = new Color(100, 116, 130),
      ACCENT = new Color(22, 108, 102);

  public App(Store store) throws Exception {
    super("ContractDesk • договоры и сроки");
    this.store = store;
    setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    setMinimumSize(new Dimension(1000, 620));
    setSize(1220, 760);
    setLocationRelativeTo(null);
    JPanel root = new JPanel(new BorderLayout(0, 18));
    root.setBorder(new EmptyBorder(24, 28, 18, 28));
    root.setBackground(new Color(245, 247, 249));
    setContentPane(root);
    JPanel top = new JPanel(new BorderLayout(0, 14));
    top.setOpaque(false);
    JLabel title = new JLabel("ContractDesk");
    title.setFont(new Font("Segoe UI", Font.BOLD, 30));
    title.setForeground(INK);
    JLabel subtitle = new JLabel("Договоры, оплаты и сроки в одном рабочем пространстве");
    subtitle.setForeground(MUTED);
    JPanel branding = new JPanel(new GridLayout(2, 1, 0, 4));
    branding.setOpaque(false);
    branding.add(title);
    branding.add(subtitle);
    top.add(branding, BorderLayout.NORTH);
    summary.setFont(new Font("Segoe UI", Font.BOLD, 15));
    summary.setForeground(ACCENT);
    summary.setBorder(new EmptyBorder(12, 0, 6, 0));
    top.add(summary, BorderLayout.CENTER);
    JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    tools.setOpaque(false);
    tools.add(new JLabel("Поиск"));
    tools.add(search);
    tools.add(filter);
    tools.add(button("Обновить", this::refresh));
    tools.add(button("Экспорт CSV", this::export));
    top.add(tools, BorderLayout.SOUTH);
    root.add(top, BorderLayout.NORTH);
    tabs.addTab(
        "Договоры",
        panel(
            contractsTable,
            button("Новый договор", () -> editContract(null)),
            button("Изменить", () -> editContract(selectedContract())),
            button("Внести оплату", this::payment),
            button("История оплат", this::history),
            button("Удалить", this::deleteContract)));
    tabs.addTab(
        "Клиенты",
        panel(
            clientsTable,
            button("Новый клиент", () -> editClient(null)),
            button("Изменить", () -> editClient(selectedClient())),
            button("Удалить", this::deleteClient)));
    tabs.addTab("Данные и резервные копии", dataPanel());
    root.add(tabs, BorderLayout.CENTER);
    footer.setForeground(MUTED);
    footer.setFont(new Font("Segoe UI", Font.PLAIN, 11));
    root.add(footer, BorderLayout.SOUTH);
    search
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                applyFilter();
              }

              public void removeUpdate(DocumentEvent e) {
                applyFilter();
              }

              public void changedUpdate(DocumentEvent e) {
                applyFilter();
              }
            });
    filter.addActionListener(e -> applyFilter());
    filter.setPreferredSize(new Dimension(145, 26));
    int[] widths = {45, 240, 200, 110, 110, 110, 115, 115};
    for (int i = 0; i < widths.length; i++)
      contractsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    contractsTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) run(() -> editContract(selectedContract()));
          }
        });
    clientsTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) run(() -> editClient(selectedClient()));
          }
        });
    getRootPane()
        .registerKeyboardAction(
            e -> search.requestFocusInWindow(),
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    new javax.swing.Timer(60_000, e -> run(this::refresh)).start();
    refresh();
  }

  private static DefaultTableModel model(String... headers) {
    return new DefaultTableModel(headers, 0) {
      @Override
      public boolean isCellEditable(int r, int c) {
        return false;
      }

      @Override
      public Class<?> getColumnClass(int c) {
        return c == 0
            ? Long.class
            : headers.length == 8 && c >= 3 && c <= 5 ? BigDecimal.class : Object.class;
      }
    };
  }

  private static JTable table(DefaultTableModel model) {
    JTable t = new JTable(model);
    t.setRowHeight(36);
    t.setAutoCreateRowSorter(true);
    t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    t.setShowVerticalLines(false);
    t.setGridColor(new Color(230, 235, 239));
    t.getTableHeader().setReorderingAllowed(false);
    t.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
    t.setDefaultRenderer(
        Object.class,
        new DefaultTableCellRenderer() {
          @Override
          public Component getTableCellRendererComponent(
              JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            Component c =
                super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            if (!selected) {
              c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 250, 251));
              c.setForeground("Просрочен".equals(value) ? new Color(177, 49, 42) : INK);
            }
            setBorder(new EmptyBorder(0, 10, 0, 8));
            return c;
          }
        });
    return t;
  }

  @FunctionalInterface
  private interface Action {
    void execute() throws Exception;
  }

  private void run(Action action) {
    try {
      action.execute();
    } catch (Exception e) {
      String message = e.getMessage();
      if (message != null && message.contains("FOREIGN KEY"))
        message = "У клиента есть договоры. Сначала перенесите или удалите их.";
      JOptionPane.showMessageDialog(
          this,
          message == null ? e.toString() : message,
          "Не удалось выполнить действие",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private JButton button(String label, Action action) {
    JButton b = new JButton(label);
    b.setFocusPainted(false);
    b.addActionListener(e -> run(action));
    return b;
  }

  private JPanel panel(JTable table, JButton... buttons) {
    JPanel p = new JPanel(new BorderLayout(0, 10));
    p.setBorder(new EmptyBorder(12, 0, 0, 0));
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    for (JButton b : buttons) bar.add(b);
    p.add(bar, BorderLayout.NORTH);
    p.add(new JScrollPane(table), BorderLayout.CENTER);
    return p;
  }

  private JPanel dataPanel() {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.setBorder(new EmptyBorder(28, 24, 24, 24));
    for (String text :
        List.of(
            "Локальные данные: без регистрации и внешнего сервера.",
            "Резервная копия содержит клиентов, договоры, заметки и историю оплат.",
            "Перед восстановлением текущая база автоматически сохраняется рядом с рабочей.",
            "Загрузка демо доступна только для пустой базы. Все примеры вымышленные.")) {
      JLabel l = new JLabel(text);
      l.setBorder(new EmptyBorder(0, 0, 12, 0));
      p.add(l);
    }
    p.add(
        button(
            "Создать резервную копию",
            () -> {
              Path f = choose(true, "contractdesk-backup-" + LocalDate.now() + ".db");
              if (f != null) {
                store.backup(f);
                info("Резервная копия сохранена:\n" + f);
              }
            }));
    p.add(Box.createVerticalStrut(10));
    p.add(
        button(
            "Восстановить из резервной копии",
            () -> {
              Path f = choose(false, null);
              if (f != null
                  && confirm(
                      "Заменить рабочую базу данными выбранной копии?\n"
                          + "Текущие данные будут сохранены отдельной резервной копией.")) {
                Path recovery = store.restore(f);
                refresh();
                info("Восстановление завершено. Предыдущая база:\n" + recovery);
              }
            }));
    p.add(Box.createVerticalStrut(10));
    p.add(
        button(
            "Загрузить демонстрационные данные",
            () -> {
              store.seedDemo();
              refresh();
            }));
    p.add(Box.createVerticalStrut(20));
    p.add(new JLabel("Автор: Никита • Fr1bilder"));
    return p;
  }

  private void refresh() throws Exception {
    contracts = store.contracts();
    clients = store.clients();
    clientsModel.setRowCount(0);
    for (Client c : clients)
      clientsModel.addRow(new Object[] {c.id(), c.name(), c.contact(), c.notes()});
    applyFilter();
    footer.setText("База: " + store.path() + "    •    Суммы в рублях    •    Ctrl+F: поиск");
  }

  private List<Contract> visible() {
    String query = search.getText().trim().toLowerCase(Locale.ROOT),
        status = Objects.toString(filter.getSelectedItem());
    return contracts.stream()
        .filter(
            c ->
                (c.title() + " " + c.clientName() + " " + c.notes() + " " + c.id())
                    .toLowerCase(Locale.ROOT)
                    .contains(query))
        .filter(
            c -> status.equals("Все статусы") || c.displayStatus(LocalDate.now()).equals(status))
        .toList();
  }

  private void applyFilter() {
    contractsModel.setRowCount(0);
    List<Contract> rows = visible();
    long total = 0, paid = 0;
    int late = 0;
    for (Contract c : rows) {
      contractsModel.addRow(
          new Object[] {
            c.id(),
            c.title(),
            c.clientName(),
            BigDecimal.valueOf(c.amount(), 2),
            BigDecimal.valueOf(c.paid(), 2),
            BigDecimal.valueOf(c.outstanding(), 2),
            c.deadline(),
            c.displayStatus(LocalDate.now())
          });
      total += c.amount();
      paid += c.paid();
      if (c.displayStatus(LocalDate.now()).equals("Просрочен")) late++;
    }
    summary.setText(
        "Договоров: "
            + rows.size()
            + "     ·     Сумма: "
            + Money.format(total)
            + " ₽     ·     Оплачено: "
            + Money.format(paid)
            + " ₽     ·     Просрочено: "
            + late);
  }

  private long selectedId(JTable table) {
    int row = table.getSelectedRow();
    if (row < 0) throw new IllegalArgumentException("Сначала выберите запись в таблице.");
    return (Long) table.getValueAt(row, 0);
  }

  private Contract selectedContract() {
    long id = selectedId(contractsTable);
    return contracts.stream().filter(c -> c.id() == id).findFirst().orElseThrow();
  }

  private Client selectedClient() {
    long id = selectedId(clientsTable);
    return clients.stream().filter(c -> c.id() == id).findFirst().orElseThrow();
  }

  private boolean confirm(String text) {
    return JOptionPane.showConfirmDialog(this, text, "Подтверждение", JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }

  private void info(String text) {
    JOptionPane.showMessageDialog(this, text, "ContractDesk", JOptionPane.INFORMATION_MESSAGE);
  }

  private Path choose(boolean save, String name) {
    JFileChooser chooser = new JFileChooser();
    if (name != null) chooser.setSelectedFile(new java.io.File(name));
    if ((save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this))
        != JFileChooser.APPROVE_OPTION) return null;
    Path f = chooser.getSelectedFile().toPath();
    if (save && Files.exists(f) && !confirm("Файл существует. Перезаписать его?")) return null;
    return f;
  }

  private JPanel form(String[] labels, JComponent... fields) {
    JPanel p = new JPanel(new GridBagLayout());
    p.setPreferredSize(new Dimension(560, labels.length * 56));
    GridBagConstraints g = new GridBagConstraints();
    g.insets = new Insets(7, 6, 7, 6);
    g.fill = GridBagConstraints.HORIZONTAL;
    for (int i = 0; i < labels.length; i++) {
      g.gridy = i;
      g.gridx = 0;
      g.weightx = 0;
      p.add(new JLabel(labels[i]), g);
      g.gridx = 1;
      g.weightx = 1;
      p.add(fields[i], g);
    }
    return p;
  }

  private void editClient(Client client) throws Exception {
    JTextField name = new JTextField(client == null ? "" : client.name()),
        contact = new JTextField(client == null ? "" : client.contact());
    JTextArea notes = new JTextArea(client == null ? "" : client.notes(), 3, 30);
    notes.setLineWrap(true);
    JPanel form =
        form(
            new String[] {"Клиент *", "Контакт", "Заметки"}, name, contact, new JScrollPane(notes));
    while (JOptionPane.showConfirmDialog(
            this,
            form,
            client == null ? "Новый клиент" : "Изменить клиента",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      try {
        store.saveClient(
            client == null ? 0 : client.id(), name.getText(), contact.getText(), notes.getText());
        refresh();
        return;
      } catch (IllegalArgumentException e) {
        info(e.getMessage());
      }
    }
  }

  private void editContract(Contract contract) throws Exception {
    if (clients.isEmpty()) {
      info("Сначала добавьте клиента на вкладке «Клиенты».");
      return;
    }
    JComboBox<Client> client = new JComboBox<>(clients.toArray(Client[]::new));
    if (contract != null)
      clients.stream()
          .filter(c -> c.id() == contract.clientId())
          .findFirst()
          .ifPresent(client::setSelectedItem);
    JTextField title = new JTextField(contract == null ? "" : contract.title()),
        amount = new JTextField(contract == null ? "0.00" : Money.format(contract.amount())),
        deadline =
            new JTextField(
                contract == null
                    ? LocalDate.now().plusDays(14).toString()
                    : contract.deadline().toString());
    JComboBox<String> status = new JComboBox<>(new String[] {"В работе", "Завершён", "Отменён"});
    if (contract != null) status.setSelectedItem(contract.status());
    JTextArea notes = new JTextArea(contract == null ? "" : contract.notes(), 3, 30);
    notes.setLineWrap(true);
    JPanel form =
        form(
            new String[] {
              "Клиент *", "Название *", "Сумма, ₽ *", "Срок ГГГГ-ММ-ДД *", "Статус", "Заметки"
            },
            client,
            title,
            amount,
            deadline,
            status,
            new JScrollPane(notes));
    while (JOptionPane.showConfirmDialog(
            this,
            form,
            contract == null ? "Новый договор" : "Изменить договор",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      try {
        LocalDate day;
        try {
          day = LocalDate.parse(deadline.getText().trim());
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Введите срок в формате ГГГГ-ММ-ДД, например 2026-12-31.");
        }
        store.saveContract(
            contract == null ? 0 : contract.id(),
            ((Client) client.getSelectedItem()).id(),
            title.getText(),
            Money.parse(amount.getText()),
            day,
            (String) status.getSelectedItem(),
            notes.getText());
        refresh();
        return;
      } catch (IllegalArgumentException e) {
        info(e.getMessage());
      }
    }
  }

  private void payment() throws Exception {
    Contract c = selectedContract();
    JTextField amount = new JTextField(Money.format(c.outstanding())), note = new JTextField();
    JPanel form = form(new String[] {"Сумма, ₽", "Комментарий"}, amount, note);
    while (JOptionPane.showConfirmDialog(
            this,
            form,
            "Оплата • " + c.title(),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      try {
        store.addPayment(c.id(), Money.parse(amount.getText()), note.getText());
        refresh();
        return;
      } catch (IllegalArgumentException e) {
        info(e.getMessage());
      }
    }
  }

  private void history() throws Exception {
    Contract c = selectedContract();
    JTextArea text = new JTextArea(store.paymentHistory(c.id()), 12, 65);
    text.setEditable(false);
    text.setLineWrap(true);
    JOptionPane.showMessageDialog(
        this, new JScrollPane(text), "Оплаты • " + c.title(), JOptionPane.PLAIN_MESSAGE);
  }

  private void deleteClient() throws Exception {
    Client c = selectedClient();
    if (confirm("Удалить клиента «" + c.name() + "»?")) {
      store.deleteClient(c.id());
      refresh();
    }
  }

  private void deleteContract() throws Exception {
    Contract c = selectedContract();
    if (confirm("Удалить договор «" + c.title() + "» и всю его историю оплат?")) {
      store.deleteContract(c.id());
      refresh();
    }
  }

  private void export() throws Exception {
    Path path = choose(true, "contracts-" + LocalDate.now() + ".csv");
    if (path != null) {
      List<Contract> rows = new ArrayList<>();
      for (int i = 0; i < contractsTable.getRowCount(); i++) {
        long id = (Long) contractsTable.getValueAt(i, 0);
        contracts.stream().filter(c -> c.id() == id).findFirst().ifPresent(rows::add);
      }
      Csv.write(path, rows);
      info("Экспортировано договоров: " + rows.size() + "\n" + path);
    }
  }

  private void remind() {
    if (reminderShown) return;
    reminderShown = true;
    long count =
        contracts.stream()
            .filter(
                c ->
                    c.status().equals("В работе")
                        && !c.deadline().isAfter(LocalDate.now().plusDays(3)))
            .count();
    if (count > 0)
      info(
          "Требуют внимания: "
              + count
              + " договор(а/ов).\nСрок наступит в ближайшие 3 дня или уже прошёл.");
  }

  public static void main(String[] args) throws Exception {
    Path data = Path.of(System.getProperty("user.home"), ".contractdesk", "contractdesk.db");
    boolean demo = false;
    Path screenshot = null;
    for (int i = 0; i < args.length; i++)
      switch (args[i]) {
        case "--data" -> data = Path.of(args[++i]);
        case "--demo" -> demo = true;
        case "--screenshot" -> screenshot = Path.of(args[++i]);
        default ->
            throw new IllegalArgumentException(
                "Аргументы: --data путь.db --demo --screenshot путь.png");
      }
    data = data.toAbsolutePath().normalize();
    Files.createDirectories(data.getParent());
    FileChannel lockChannel =
        FileChannel.open(
            data.resolveSibling(data.getFileName() + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock lock = lockChannel.tryLock();
    if (lock == null) {
      lockChannel.close();
      JOptionPane.showMessageDialog(null, "Эта база уже открыта в другом экземпляре ContractDesk.");
      return;
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    lock.release();
                    lockChannel.close();
                  } catch (Exception ignored) {
                  }
                }));
    Store store = new Store(data);
    if (demo && store.clients().isEmpty()) store.seedDemo();
    Path image = screenshot;
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 13));
    UIManager.put("Button.font", new Font("Segoe UI", Font.PLAIN, 12));
    UIManager.put("Table.font", new Font("Segoe UI", Font.PLAIN, 13));
    SwingUtilities.invokeLater(
        () -> {
          try {
            App app = new App(store);
            if (image != null) {
              app.setVisible(true);
              new javax.swing.Timer(
                  700,
                  e -> {
                    try {
                      BufferedImage bitmap =
                          new BufferedImage(
                              app.getWidth(), app.getHeight(), BufferedImage.TYPE_INT_RGB);
                      Graphics2D graphics = bitmap.createGraphics();
                      app.paint(graphics);
                      graphics.dispose();
                      javax.imageio.ImageIO.write(bitmap, "png", image.toFile());
                      app.dispose();
                      System.exit(0);
                    } catch (Exception error) {
                      error.printStackTrace();
                      System.exit(1);
                    }
                  }) {
                {
                  setRepeats(false);
                }
              }.start();
            } else {
              app.setVisible(true);
              SwingUtilities.invokeLater(app::remind);
            }
          } catch (Exception e) {
            JOptionPane.showMessageDialog(
                null, e.getMessage(), "ContractDesk", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
          }
        });
  }
}
