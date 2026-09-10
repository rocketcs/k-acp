import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * v0.2 模拟数据导入 DM8（MOCK_APP）。
 * 流程：备份 v0.1 → DROP → CREATE（结构与 v0.1 兼容）→ 批量插入 CSV。
 */
public class LoadMockV02 {
  static final String URL = "jdbc:dm://127.0.0.1:5236";
  static final String USER = "MOCK_APP", PASS = "MockApp2026";
  static final Path DIR = Paths.get("data/dm8-mock-v0.2");

  public static void main(String[] args) throws Exception {
    Class.forName("dm.jdbc.driver.DmDriver");
    try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
      c.setAutoCommit(false);
      backup(c);
      createAll(c);
      int n1 = loadProvenance(c);
      int n2 = loadCatalog(c);
      int n3 = loadWarehouse(c);
      int n4 = loadInventory(c);
      int n5 = loadSummary(c);
      c.commit();
      System.out.println("导入完成: provenance=" + n1 + " catalog=" + n2 + " warehouse=" + n3
          + " inventory=" + n4 + " summary=" + n5);
      verify(c);
    }
  }

  static void backup(Connection c) throws SQLException {
    String[] tables = {"MOCK_DATA_PROVENANCE", "MOCK_EQUIPMENT_CATALOG",
        "MM_SD_ZNCK_WAREHOUSE_INFO_M", "MM_SD_ZNCK_WAREHOUSE_DISTR_M", "MM_SD_ZTFX_WAR_MONEY_K"};
    for (String t : tables) {
      exec(c, "DROP TABLE " + t + "_V01_BAK CASCADE");
      exec(c, "CREATE TABLE " + t + "_V01_BAK AS SELECT * FROM " + t);
    }
    c.commit();
    System.out.println("已备份 v0.1 数据到 *_V01_BAK");
  }

  static void createAll(Connection c) {
    // DDL 在各 load 方法中执行
  }

  static void exec(Connection c, String sql) {
    try (Statement s = c.createStatement()) { s.execute(sql); }
    catch (SQLException e) { /* 忽略：备份表不存在等情况 */ try { c.clearWarnings(); } catch (Exception ignored) {} }
  }

  static List<String[]> read(String file) throws IOException {
    List<String> lines = Files.readAllLines(DIR.resolve(file), StandardCharsets.UTF_8);
    List<String[]> out = new ArrayList<>();
    String[] header = lines.get(0).split(",", -1);
    out.add(header);
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).isBlank()) continue;
      String[] parts = lines.get(i).split(",", -1);
      if (parts.length != header.length) throw new IOException(file + " 第" + (i + 1) + "行列数不符: " + parts.length);
      out.add(parts);
    }
    return out;
  }

  static int loadProvenance(Connection c) throws Exception {
    exec(c, "DROP TABLE MOCK_DATA_PROVENANCE CASCADE");
    exec(c, "CREATE TABLE MOCK_DATA_PROVENANCE ("
        + "SOURCE_ID VARCHAR2(256) NOT NULL, SOURCE_NAME VARCHAR2(256), SOURCE_URL VARCHAR2(500),"
        + "SOURCE_NOTE VARCHAR2(500), RETRIEVED_ON DATE, DATA_STATUS VARCHAR2(128),"
        + "CONSTRAINT MOCK_DATA_PROVENANCE_PK PRIMARY KEY (SOURCE_ID))");
    List<String[]> rows = read("provenance.csv");
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO MOCK_DATA_PROVENANCE VALUES (?,?,?,?,?,?)")) {
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        p.setString(1, r[0]); p.setString(2, r[1]); p.setString(3, r[2]);
        p.setString(4, r[3]); p.setDate(5, java.sql.Date.valueOf(r[4])); p.setString(6, r[5]);
        p.addBatch();
      }
      return execBatch(c, p);
    }
  }

  static int loadCatalog(Connection c) throws Exception {
    exec(c, "DROP TABLE MOCK_EQUIPMENT_CATALOG CASCADE");
    exec(c, "CREATE TABLE MOCK_EQUIPMENT_CATALOG ("
        + "MATERIAL_ID VARCHAR2(256) NOT NULL, MATERIAL_NAME VARCHAR2(256), MATERIAL_MODEL VARCHAR2(256),"
        + "MATERIAL_CATEGORY VARCHAR2(128), MANUFACTURER VARCHAR2(256), RATED_VOLTAGE_KV DECIMAL(20,4),"
        + "RATED_CAPACITY_KVA DECIMAL(20,4), RATED_CURRENT_A DECIMAL(20,4), UNIT_NAME VARCHAR2(64),"
        + "BASE_PRICE DECIMAL(20,4), SOURCE_ID VARCHAR2(256), SOURCE_NOTE VARCHAR2(500), IS_MOCK INT,"
        + "CONSTRAINT MOCK_EQUIPMENT_CATALOG_PK PRIMARY KEY (MATERIAL_ID))");
    List<String[]> rows = read("catalog.csv");
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO MOCK_EQUIPMENT_CATALOG VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        p.setString(1, r[0]); p.setString(2, r[1]); p.setString(3, r[2]); p.setString(4, r[3]);
        p.setString(5, r[4]); p.setBigDecimal(6, dec(r[5])); p.setBigDecimal(7, dec(r[6]));
        p.setBigDecimal(8, dec(r[7])); p.setString(9, r[8]); p.setBigDecimal(10, dec(r[9]));
        p.setString(11, r[10]); p.setString(12, r[11]); p.setInt(13, intv(r[12]));
        p.addBatch();
      }
      return execBatch(c, p);
    }
  }

  static int loadWarehouse(Connection c) throws Exception {
    exec(c, "DROP TABLE MM_SD_ZNCK_WAREHOUSE_INFO_M CASCADE");
    exec(c, "CREATE TABLE MM_SD_ZNCK_WAREHOUSE_INFO_M ("
        + "WAREHOUSE_ID VARCHAR2(256) NOT NULL, WAREHOUSE_NAME VARCHAR2(256), WAREHOUSE_CODE VARCHAR2(128),"
        + "WAREHOUSE_LEVEL INT, WAREHOUSE_LEVEL_NAME VARCHAR2(64), WAREHOUSE_ADDR VARCHAR2(500),"
        + "GEOGRAPHICAL_LNG DECIMAL(20,4), GEOGRAPHICAL_LAT DECIMAL(20,4),"
        + "IDLE_AMT DECIMAL(20,4), SCRAP_AMT DECIMAL(20,4), RESERVE_AMT DECIMAL(20,4),"
        + "PROJECT_AMT DECIMAL(20,4), TOTAL_AMT DECIMAL(20,4),"
        + "PROVINCE_CODE VARCHAR2(32), BUREAU_CODE VARCHAR2(32), DATA_SOURCE VARCHAR2(256), IS_MOCK INT,"
        + "CONSTRAINT MM_SD_ZNCK_WH_INFO_PK PRIMARY KEY (WAREHOUSE_ID))");
    List<String[]> rows = read("warehouse.csv");
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO MM_SD_ZNCK_WAREHOUSE_INFO_M VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        for (int k = 0; k < 6; k++) p.setString(k + 1, r[k]);
        p.setBigDecimal(7, dec(r[6])); p.setBigDecimal(8, dec(r[7]));
        for (int k = 8; k <= 12; k++) p.setBigDecimal(k + 1, dec(r[k]));
        p.setString(14, r[13]); p.setString(15, r[14]); p.setString(16, r[15]); p.setInt(17, intv(r[16]));
        p.addBatch();
      }
      return execBatch(c, p);
    }
  }

  static int loadInventory(Connection c) throws Exception {
    exec(c, "DROP TABLE MM_SD_ZNCK_WAREHOUSE_DISTR_M CASCADE");
    exec(c, "CREATE TABLE MM_SD_ZNCK_WAREHOUSE_DISTR_M ("
        + "WAREHOUSE_DISTR_ID VARCHAR2(256) NOT NULL, ACTUAL_WAREHOUSE_ID VARCHAR2(256),"
        + "MATERIAL_ID VARCHAR2(256), MATERIAL_NAME VARCHAR2(256), MATERIAL_MODEL VARCHAR2(256),"
        + "MATERIAL_CATEGORY VARCHAR2(128), VENDOR_ID VARCHAR2(256), VENDOR_NAME VARCHAR2(256),"
        + "PROJECT_ID VARCHAR2(256), PROJECT_NAME VARCHAR2(256), ACTUAL_QTY DECIMAL(20,4),"
        + "UNIT_PRICE DECIMAL(20,4), ACTUAL_TOTAL_PRICE DECIMAL(20,4), ACTUAL_TOTAL_TAX DECIMAL(20,4),"
        + "UNIT_NAME VARCHAR2(64), USAGE_NAME VARCHAR2(64), RECEIPT_DATE TIMESTAMP,"
        + "INVENTORY_AGE_NAME VARCHAR2(64), PROVINCE_CODE VARCHAR2(32), BUREAU_CODE VARCHAR2(32),"
        + "VOLTAGE_LEVEL_KV DECIMAL(20,4), RATED_CAPACITY_KVA DECIMAL(20,4), RATED_CURRENT_A DECIMAL(20,4),"
        + "DATA_SOURCE VARCHAR2(256), IS_MOCK INT,"
        + "CONSTRAINT MM_SD_ZNCK_WH_DISTR_PK PRIMARY KEY (WAREHOUSE_DISTR_ID))");
    List<String[]> rows = read("inventory.csv");
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO MM_SD_ZNCK_WAREHOUSE_DISTR_M VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        for (int k = 0; k < 10; k++) p.setString(k + 1, r[k]);
        p.setBigDecimal(11, dec(r[10])); p.setBigDecimal(12, dec(r[11]));
        p.setBigDecimal(13, dec(r[12])); p.setBigDecimal(14, dec(r[13]));
        p.setString(15, r[14]); p.setString(16, r[15]);
        p.setTimestamp(17, Timestamp.valueOf(r[16]));
        p.setString(18, r[17]); p.setString(19, r[18]); p.setString(20, r[19]);
        p.setBigDecimal(21, dec(r[20])); p.setBigDecimal(22, dec(r[21])); p.setBigDecimal(23, dec(r[22]));
        p.setString(24, r[23]); p.setInt(25, intv(r[24]));
        p.addBatch();
      }
      return execBatch(c, p);
    }
  }

  static int loadSummary(Connection c) throws Exception {
    exec(c, "DROP TABLE MM_SD_ZTFX_WAR_MONEY_K CASCADE");
    exec(c, "CREATE TABLE MM_SD_ZTFX_WAR_MONEY_K ("
        + "SEQ_ID VARCHAR2(64) NOT NULL, TIME_ID VARCHAR2(16), USAGE_TYPE_ID INT, WAREHOUSE_LEVEL_ID INT,"
        + "WAREHOUSE_MONEY DECIMAL(20,4), WAREHOUSE_PRE_MONEY DECIMAL(20,4),"
        + "PROVINCE_CODE VARCHAR2(32), BUREAU_CODE VARCHAR2(32), DATA_SOURCE VARCHAR2(256), IS_MOCK INT,"
        + "CONSTRAINT MM_SD_ZTFX_WAR_MONEY_PK PRIMARY KEY (SEQ_ID))");
    List<String[]> rows = read("summary.csv");
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO MM_SD_ZTFX_WAR_MONEY_K VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        p.setString(1, r[0]); p.setString(2, r[1]); p.setInt(3, intv(r[2])); p.setInt(4, intv(r[3]));
        p.setBigDecimal(5, dec(r[4])); p.setBigDecimal(6, dec(r[5]));
        p.setString(7, r[6]); p.setString(8, r[7]); p.setString(9, r[8]); p.setInt(10, intv(r[9]));
        p.addBatch();
      }
      return execBatch(c, p);
    }
  }

  static int execBatch(Connection c, PreparedStatement p) throws SQLException {
    int[] r = p.executeBatch();
    c.commit();
    return r.length;
  }

  static BigDecimal dec(String v) { return v == null || v.isBlank() ? null : new BigDecimal(v); }
  static int intv(String v) { return v == null || v.isBlank() ? 0 : (int) Double.parseDouble(v); }

  static void verify(Connection c) throws SQLException {
    String[] tables = {"MOCK_DATA_PROVENANCE", "MOCK_EQUIPMENT_CATALOG",
        "MM_SD_ZNCK_WAREHOUSE_INFO_M", "MM_SD_ZNCK_WAREHOUSE_DISTR_M", "MM_SD_ZTFX_WAR_MONEY_K"};
    try (Statement s = c.createStatement()) {
      for (String t : tables) {
        try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
          r.next();
          System.out.println("验证 " + t + " = " + r.getLong(1));
        }
      }
      try (ResultSet r = s.executeQuery(
          "SELECT COUNT(*) FROM MM_SD_ZNCK_WAREHOUSE_DISTR_M WHERE ABS(ACTUAL_QTY*UNIT_PRICE-ACTUAL_TOTAL_PRICE)>0.01")) {
        r.next();
        System.out.println("验证 金额一致性异常 = " + r.getLong(1));
      }
    }
  }
}
