package com.keyloop.scheduler;

import java.util.UUID;

public final class SeedIds {

  public static final UUID LONDON_DEALERSHIP_ID =
      UUID.fromString("00000000-0000-0000-0001-000000000001");
  public static final UUID NEW_YORK_DEALERSHIP_ID =
      UUID.fromString("00000000-0000-0000-0001-000000000002");
  public static final UUID LONDON_BAY_1_ID =
      UUID.fromString("00000000-0000-0000-0002-000000000101");
  public static final UUID LONDON_BAY_2_ID =
      UUID.fromString("00000000-0000-0000-0002-000000000102");
  public static final UUID LONDON_BAY_3_ID =
      UUID.fromString("00000000-0000-0000-0002-000000000103");
  public static final UUID LONDON_TECH_1_ID =
      UUID.fromString("00000000-0000-0000-0003-000000000101");
  public static final UUID LONDON_TECH_3_ID =
      UUID.fromString("00000000-0000-0000-0003-000000000103");
  public static final UUID LONDON_TECH_2_ID =
      UUID.fromString("00000000-0000-0000-0003-000000000102");
  public static final UUID CUSTOMER_1_ID = UUID.fromString("00000000-0000-0000-0004-000000000001");
  public static final UUID VEHICLE_1_ID = UUID.fromString("00000000-0000-0000-0005-000000000001");
  public static final UUID VEHICLE_2_ID = UUID.fromString("00000000-0000-0000-0005-000000000002");

  public static UUID vehicle(int number) {
    return UUID.fromString(String.format("00000000-0000-0000-0005-%012d", number));
  }

  private SeedIds() {}
}
