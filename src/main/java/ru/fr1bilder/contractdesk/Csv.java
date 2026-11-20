package ru.fr1bilder.contractdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Csv {
  private Csv() {}

  public static String cell(String value) {
    String safe = value == null ? "" : value;
    String leading = safe.stripLeading();
    if ((!safe.isEmpty() && "\t\r".indexOf(safe.charAt(0)) >= 0)
        || (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)) safe = "'" + safe;
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }

  public static void write(Path path, List<Contract> rows) throws IOException {
    StringBuilder out =
        new StringBuilder(
            "\uFEFFID;Клиент;Договор;Сумма;Оплачено;Остаток;Дедлайн;Статус;Заметки\r\n");
    for (Contract c : rows) {
      String[] cells = {
        String.valueOf(c.id()),
        c.clientName(),
        c.title(),
        Money.format(c.amount()),
        Money.format(c.paid()),
        Money.format(c.outstanding()),
        c.deadline().toString(),
        c.displayStatus(java.time.LocalDate.now()),
        c.notes()
      };
      for (int i = 0; i < cells.length; i++) out.append(i == 0 ? "" : ";").append(cell(cells[i]));
      out.append("\r\n");
    }
    Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
  }
}
