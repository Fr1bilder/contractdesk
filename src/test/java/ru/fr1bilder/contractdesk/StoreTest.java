package ru.fr1bilder.contractdesk;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path temp;

  private Store database() throws Exception {
    return new Store(temp.resolve("main.db"));
  }

  @Test
  void tracksPaymentsAndPreventsOverpayment() throws Exception {
    Store s = database();
    s.saveClient(0, "Клиент", "Контакт", "");
    long client = s.clients().get(0).id();
    s.saveContract(0, client, "Договор", 10000, LocalDate.now(), "В работе", "");
    long id = s.contracts().get(0).id();
    s.addPayment(id, 2500, "Аванс");
    assertEquals(7500, s.contracts().get(0).outstanding());
    assertThrows(IllegalArgumentException.class, () -> s.addPayment(id, 8000, "Ошибка"));
    assertThrows(
        IllegalArgumentException.class,
        () -> s.saveContract(id, client, "Договор", 2000, LocalDate.now(), "В работе", ""));
    assertEquals(2500, s.contracts().get(0).paid());
    assertTrue(s.paymentHistory(id).contains("Аванс"));
  }

  @Test
  void preservesReferentialIntegrityAndCascadesPaymentDeletion() throws Exception {
    Store s = database();
    s.seedDemo();
    Contract c = s.contracts().get(0);
    assertThrows(SQLException.class, () -> s.deleteClient(c.clientId()));
    s.deleteContract(c.id());
    assertEquals("Оплат пока нет.", s.paymentHistory(c.id()));
  }

  @Test
  void restoresSnapshotAndRetainsPreviousDatabase() throws Exception {
    Store s = database();
    s.seedDemo();
    Path backup = temp.resolve("backup.db");
    s.backup(backup);
    s.saveClient(0, "Новый клиент", "", "");
    int newerCount = s.clients().size();
    Path recovery = s.restore(backup);
    assertEquals(newerCount - 1, s.clients().size());
    assertTrue(Files.isRegularFile(recovery));
    assertEquals(newerCount, new Store(recovery).clients().size());
    assertThrows(java.io.IOException.class, () -> s.backup(backup));
  }

  @Test
  void refusesInvalidRestoreWithoutChangingLiveData() throws Exception {
    Store s = database();
    s.seedDemo();
    Path bad = temp.resolve("bad.db");
    Files.writeString(bad, "not a database");
    assertThrows(Exception.class, () -> s.restore(bad));
    assertEquals(3, s.contracts().size());
  }

  @Test
  void restoresCommittedWalChangesAsOneSnapshot() throws Exception {
    Store target = database();
    target.seedDemo();
    Path sourcePath = temp.resolve("wal-source.db");
    Store source = new Store(sourcePath);
    source.saveClient(0, "Первый клиент", "", "");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sourcePath);
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute("PRAGMA wal_autocheckpoint=0");
      statement.execute("INSERT INTO clients(name,contact,notes) VALUES('Клиент из WAL','','')");
      assertTrue(Files.size(Path.of(sourcePath + "-wal")) > 0);
      target.restore(sourcePath);
      assertEquals(2, target.clients().size());
      assertTrue(target.clients().stream().anyMatch(c -> c.name().equals("Клиент из WAL")));
    }
  }

  @Test
  void rejectsOverpaidRestoreAndCleansStagingFile() throws Exception {
    Store target = database();
    target.seedDemo();
    Path backup = temp.resolve("overpaid.db");
    target.backup(backup);
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
        Statement statement = connection.createStatement()) {
      statement.execute("UPDATE payments SET amount=999999999");
    }
    assertThrows(java.io.IOException.class, () -> target.restore(backup));
    assertEquals(3, target.contracts().size());
    try (var files = Files.list(temp)) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("restore-")));
    }
  }

  @Test
  void distinguishesOverdueFromCompleted() {
    Contract active =
        new Contract(1, 1, "A", "B", 100, 0, LocalDate.now().minusDays(1), "В работе", "");
    assertEquals("Просрочен", active.displayStatus(LocalDate.now()));
    Contract done =
        new Contract(2, 1, "A", "B", 100, 100, LocalDate.now().minusDays(1), "Завершён", "");
    assertEquals("Завершён", done.displayStatus(LocalDate.now()));
  }

  @Test
  void parsesMoneyExactlyAndRejectsFractionsOfKopeck() {
    assertEquals(123456, Money.parse("1 234,56"));
    assertEquals("1234.56", Money.format(123456));
    assertThrows(IllegalArgumentException.class, () -> Money.parse("1.001"));
    assertThrows(IllegalArgumentException.class, () -> Money.parse("-1"));
  }

  @Test
  void csvEscapesQuotesMultilineAndFormulaInjection() throws Exception {
    assertEquals("\"' =x\"", Csv.cell(" =x"));
    assertEquals("\"'=SUM(A1)\"", Csv.cell("=SUM(A1)"));
    Store s = database();
    s.saveClient(0, "Клиент;\"А\"", "", "");
    s.saveContract(
        0, s.clients().get(0).id(), "Название", 123, LocalDate.now(), "В работе", "Строка\nещё");
    Path csv = temp.resolve("out.csv");
    Csv.write(csv, s.contracts());
    String text = Files.readString(csv);
    assertTrue(text.startsWith("\uFEFF"));
    assertTrue(text.contains("\"Клиент;\"\"А\"\"\""));
    assertTrue(text.contains("\"Строка\nещё\""));
  }
}
