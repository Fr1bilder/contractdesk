package ru.fr1bilder.contractdesk;

import java.time.LocalDate;

public record Contract(
    long id,
    long clientId,
    String clientName,
    String title,
    long amount,
    long paid,
    LocalDate deadline,
    String status,
    String notes) {
  public String displayStatus(LocalDate today) {
    return status.equals("В работе") && deadline.isBefore(today) ? "Просрочен" : status;
  }

  public long outstanding() {
    return amount - paid;
  }
}
