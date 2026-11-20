package ru.fr1bilder.contractdesk;

public record Client(long id, String name, String contact, String notes) {
  @Override
  public String toString() {
    return name;
  }
}
