package ru.fr1bilder.contractdesk;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
  private Money() {}

  public static long parse(String value) {
    try {
      long result =
          new BigDecimal(value.trim().replace(" ", "").replace(',', '.'))
              .setScale(2, RoundingMode.UNNECESSARY)
              .movePointRight(2)
              .longValueExact();
      if (result < 0 || result > 999_999_999_999_99L) throw new ArithmeticException();
      return result;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Введите неотрицательную сумму с максимум двумя знаками после запятой.");
    }
  }

  public static String format(long kopecks) {
    return BigDecimal.valueOf(kopecks, 2).toPlainString();
  }
}
