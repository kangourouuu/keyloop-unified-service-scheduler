package com.keyloop.scheduler.common.util;

import org.postgresql.util.PSQLException;

public final class SqlErrorUtils {

  private SqlErrorUtils() {}

  public static String sqlState(Throwable error) {
    PSQLException psql = findPsqlException(error);
    return psql == null ? "" : psql.getSQLState();
  }

  public static String constraintName(Throwable error) {
    PSQLException psql = findPsqlException(error);
    if (psql == null || psql.getServerErrorMessage() == null) {
      return "";
    }
    String constraint = psql.getServerErrorMessage().getConstraint();
    return constraint == null ? "" : constraint;
  }

  private static PSQLException findPsqlException(Throwable error) {
    Throwable current = error;
    while (current != null && !(current instanceof PSQLException)) {
      current = current.getCause();
    }
    return (PSQLException) current;
  }
}
