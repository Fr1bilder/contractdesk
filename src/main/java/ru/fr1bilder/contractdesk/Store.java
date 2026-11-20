package ru.fr1bilder.contractdesk;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class Store {
  private final Path path;

  public Store(Path path) throws SQLException, IOException {
    this.path = path.toAbsolutePath().normalize();
    Files.createDirectories(this.path.getParent());
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
        if (r.next() && r.getInt(1) > 1)
          throw new SQLException("База создана более новой версией ContractDesk.");
      }
      s.execute(
          "CREATE TABLE IF NOT EXISTS clients(id INTEGER PRIMARY KEY, name TEXT NOT NULL, contact"
              + " TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '')");
      s.execute(
          "CREATE TABLE IF NOT EXISTS contracts(id INTEGER PRIMARY KEY, client_id INTEGER NOT NULL"
              + " REFERENCES clients(id) ON DELETE RESTRICT, title TEXT NOT NULL, amount INTEGER"
              + " NOT NULL CHECK(amount>=0), deadline TEXT NOT NULL, status TEXT NOT NULL"
              + " CHECK(status IN ('В работе','Завершён','Отменён')), notes TEXT NOT NULL DEFAULT"
              + " '')");
      s.execute(
          "CREATE TABLE IF NOT EXISTS payments(id INTEGER PRIMARY KEY, contract_id INTEGER NOT NULL"
              + " REFERENCES contracts(id) ON DELETE CASCADE, amount INTEGER NOT NULL"
              + " CHECK(amount>0), created TEXT NOT NULL, note TEXT NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS idx_contract_deadline ON contracts(deadline)");
      s.execute("CREATE INDEX IF NOT EXISTS idx_payment_contract ON payments(contract_id)");
      s.execute("PRAGMA user_version=1");
    }
  }

  public Path path() {
    return path;
  }

  private Connection connect() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=5000");
    }
    return c;
  }

  public synchronized List<Client> clients() throws SQLException {
    List<Client> rows = new ArrayList<>();
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM clients ORDER BY name COLLATE NOCASE")) {
      while (r.next())
        rows.add(
            new Client(
                r.getLong("id"),
                r.getString("name"),
                r.getString("contact"),
                r.getString("notes")));
    }
    return rows;
  }

  private static String required(String s, String name) {
    if (s == null || s.isBlank())
      throw new IllegalArgumentException(name + " не может быть пустым.");
    return s.trim();
  }

  public synchronized void saveClient(long id, String name, String contact, String notes)
      throws SQLException {
    name = required(name, "Имя клиента");
    try (Connection c = connect();
        PreparedStatement s =
            c.prepareStatement(
                id == 0
                    ? "INSERT INTO clients(name,contact,notes) VALUES(?,?,?)"
                    : "UPDATE clients SET name=?,contact=?,notes=? WHERE id=?")) {
      s.setString(1, name);
      s.setString(2, contact.trim());
      s.setString(3, notes.trim());
      if (id != 0) s.setLong(4, id);
      s.executeUpdate();
    }
  }

  public synchronized void deleteClient(long id) throws SQLException {
    try (Connection c = connect();
        PreparedStatement s = c.prepareStatement("DELETE FROM clients WHERE id=?")) {
      s.setLong(1, id);
      s.executeUpdate();
    }
  }

  public synchronized List<Contract> contracts() throws SQLException {
    List<Contract> rows = new ArrayList<>();
    String sql =
        "SELECT t.*,c.name,COALESCE((SELECT SUM(p.amount) FROM payments p WHERE"
            + " p.contract_id=t.id),0) paid FROM contracts t JOIN clients c ON c.id=t.client_id"
            + " ORDER BY t.deadline,t.id";
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      while (r.next())
        rows.add(
            new Contract(
                r.getLong("id"),
                r.getLong("client_id"),
                r.getString("name"),
                r.getString("title"),
                r.getLong("amount"),
                r.getLong("paid"),
                LocalDate.parse(r.getString("deadline")),
                r.getString("status"),
                r.getString("notes")));
    }
    return rows;
  }

  public synchronized void saveContract(
      long id,
      long clientId,
      String title,
      long amount,
      LocalDate deadline,
      String status,
      String notes)
      throws SQLException {
    title = required(title, "Название договора");
    if (amount < 0
        || deadline == null
        || !List.of("В работе", "Завершён", "Отменён").contains(status))
      throw new IllegalArgumentException("Проверьте сумму, срок и статус.");
    try (Connection c = connect()) {
      if (id != 0)
        try (PreparedStatement p =
            c.prepareStatement(
                "SELECT COALESCE(SUM(amount),0) FROM payments WHERE contract_id=?")) {
          p.setLong(1, id);
          try (ResultSet r = p.executeQuery()) {
            if (r.next() && amount < r.getLong(1))
              throw new IllegalArgumentException("Сумма договора меньше уже внесённых оплат.");
          }
        }
      try (PreparedStatement s =
          c.prepareStatement(
              id == 0
                  ? "INSERT INTO contracts(client_id,title,amount,deadline,status,notes)"
                      + " VALUES(?,?,?,?,?,?)"
                  : "UPDATE contracts SET client_id=?,title=?,amount=?,deadline=?,status=?,notes=?"
                      + " WHERE id=?")) {
        s.setLong(1, clientId);
        s.setString(2, title);
        s.setLong(3, amount);
        s.setString(4, deadline.toString());
        s.setString(5, status);
        s.setString(6, notes.trim());
        if (id != 0) s.setLong(7, id);
        s.executeUpdate();
      }
    }
  }

  public synchronized void deleteContract(long id) throws SQLException {
    try (Connection c = connect();
        PreparedStatement s = c.prepareStatement("DELETE FROM contracts WHERE id=?")) {
      s.setLong(1, id);
      s.executeUpdate();
    }
  }

  public synchronized void addPayment(long id, long amount, String note) throws SQLException {
    if (amount <= 0) throw new IllegalArgumentException("Оплата должна быть больше нуля.");
    try (Connection c = connect();
        Statement lock = c.createStatement()) {
      lock.execute("BEGIN IMMEDIATE");
      try {
        try (PreparedStatement s =
            c.prepareStatement(
                "SELECT amount-COALESCE((SELECT SUM(amount) FROM payments WHERE"
                    + " contract_id=contracts.id),0) FROM contracts WHERE id=?")) {
          s.setLong(1, id);
          try (ResultSet r = s.executeQuery()) {
            if (!r.next() || amount > r.getLong(1))
              throw new IllegalArgumentException("Оплата превышает остаток по договору.");
          }
        }
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO payments(contract_id,amount,created,note) VALUES(?,?,?,?)")) {
          s.setLong(1, id);
          s.setLong(2, amount);
          s.setString(3, LocalDateTime.now().toString());
          s.setString(4, note.trim());
          s.executeUpdate();
        }
        lock.execute("COMMIT");
      } catch (Exception e) {
        lock.execute("ROLLBACK");
        throw e;
      }
    }
  }

  public synchronized String paymentHistory(long id) throws SQLException {
    StringBuilder text = new StringBuilder();
    try (Connection c = connect();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT * FROM payments WHERE contract_id=? ORDER BY created DESC")) {
      s.setLong(1, id);
      try (ResultSet r = s.executeQuery()) {
        while (r.next())
          text.append(r.getString("created").replace('T', ' '))
              .append("  |  ")
              .append(Money.format(r.getLong("amount")))
              .append(" ₽  |  ")
              .append(r.getString("note"))
              .append('\n');
      }
    }
    return text.isEmpty() ? "Оплат пока нет." : text.toString();
  }

  public synchronized void backup(Path target) throws SQLException, IOException {
    target = target.toAbsolutePath().normalize();
    if (Files.exists(target))
      throw new IOException("Выберите новое имя: файл резервной копии уже существует.");
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      s.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
    }
  }

  public synchronized Path restore(Path source) throws SQLException, IOException {
    source = source.toAbsolutePath().normalize();
    if (source.equals(path)) throw new IOException("Выберите резервную копию, а не рабочую базу.");
    if (!Files.isRegularFile(source)) throw new IOException("Файл резервной копии не найден.");
    if (Files.isSameFile(source, path))
      throw new IOException("Выберите резервную копию, а не рабочую базу.");
    Path staging = Files.createTempFile(path.getParent(), "restore-", ".tmp");
    try {
      try (Connection c =
              DriverManager.getConnection(
                  "jdbc:sqlite:file:" + source.toUri().getRawPath() + "?mode=ro");
          Statement s = c.createStatement()) {
        s.execute("PRAGMA busy_timeout=5000");
        s.execute("VACUUM INTO '" + staging.toString().replace("'", "''") + "'");
      }
      validateSnapshot(staging);
      Path recovery =
          path.resolveSibling(
              "before-restore-"
                  + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                  + ".db");
      backup(recovery);
      try {
        Files.move(
            staging, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
      }
      return recovery;
    } finally {
      Files.deleteIfExists(staging);
    }
  }

  private static void validateSnapshot(Path source) throws SQLException, IOException {
    try (Connection c =
            DriverManager.getConnection(
                "jdbc:sqlite:file:" + source.toUri().getRawPath() + "?mode=ro");
        Statement s = c.createStatement()) {
      try (ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
        if (!r.next() || !r.getString(1).equals("ok")) throw new IOException("База повреждена.");
      }
      try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
        if (!r.next() || r.getInt(1) != 1) throw new IOException("Неподдерживаемая версия базы.");
      }
      s.executeQuery(
              "SELECT"
                  + " c.id,c.name,c.contact,c.notes,t.id,t.client_id,t.title,t.amount,t.deadline,t.status,t.notes,p.id,p.contract_id,p.amount,p.created,p.note"
                  + " FROM clients c LEFT JOIN contracts t ON t.client_id=c.id LEFT JOIN payments p"
                  + " ON p.contract_id=t.id LIMIT 1")
          .close();
      try (ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
        if (r.next()) throw new IOException("В копии нарушены связи между записями.");
      }
      try (ResultSet r = s.executeQuery("SELECT deadline,status,amount FROM contracts")) {
        while (r.next()) {
          try {
            LocalDate.parse(r.getString(1));
          } catch (RuntimeException e) {
            throw new IOException("В копии некорректные даты.");
          }
          if (!List.of("В работе", "Завершён", "Отменён").contains(r.getString(2))
              || r.getLong(3) < 0) throw new IOException("В копии некорректные договоры.");
        }
      }
      try (ResultSet r =
          s.executeQuery(
              "SELECT 1 FROM contracts t WHERE (SELECT COALESCE(SUM(amount),0) FROM payments p"
                  + " WHERE p.contract_id=t.id)>t.amount LIMIT 1")) {
        if (r.next()) throw new IOException("В копии сумма оплат превышает сумму договора.");
      }
      try (ResultSet r =
          s.executeQuery("SELECT 1 FROM payments WHERE amount IS NULL OR amount<=0 LIMIT 1")) {
        if (r.next()) throw new IOException("В копии некорректные оплаты.");
      }
    }
  }

  public synchronized void seedDemo() throws SQLException {
    if (!clients().isEmpty() || !contracts().isEmpty())
      throw new IllegalArgumentException("Демо можно загрузить только в пустую базу.");
    saveClient(
        0, "Студия Север (демо)", "demo@example.com", "Вымышленный клиент для демонстрации.");
    saveClient(0, "Мастерская Точка (демо)", "+7 000 000-00-00", "Вымышленные данные.");
    List<Client> customers = clients();
    saveContract(
        0,
        customers.get(0).id(),
        "Сайт мастерской",
        8500000,
        LocalDate.now().plusDays(5),
        "В работе",
        "Главная, каталог услуг, форма заявки.");
    saveContract(
        0,
        customers.get(1).id(),
        "Автоматизация отчётов",
        4200000,
        LocalDate.now().minusDays(2),
        "В работе",
        "Ожидается согласование результата.");
    saveContract(
        0,
        customers.get(0).id(),
        "Техническая поддержка",
        1200000,
        LocalDate.now().plusDays(20),
        "В работе",
        "Ежемесячное обслуживание.");
    addPayment(contracts().get(0).id(), 1000000, "Аванс, демонстрационная запись");
  }
}
