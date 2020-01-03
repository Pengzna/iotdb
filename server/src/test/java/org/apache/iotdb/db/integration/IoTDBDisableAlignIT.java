package org.apache.iotdb.db.integration;

import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.jdbc.Config;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.*;

import static org.junit.Assert.fail;

public class IoTDBDisableAlignIT {
  
  private static IoTDB daemon;
  private static String[] sqls = new String[]{

      "SET STORAGE GROUP TO root.vehicle",
      "SET STORAGE GROUP TO root.other",

      "CREATE TIMESERIES root.vehicle.d0.s0 WITH DATATYPE=INT32, ENCODING=RLE",
      "CREATE TIMESERIES root.vehicle.d0.s1 WITH DATATYPE=INT64, ENCODING=RLE",
      "CREATE TIMESERIES root.vehicle.d0.s2 WITH DATATYPE=FLOAT, ENCODING=RLE",
      "CREATE TIMESERIES root.vehicle.d0.s3 WITH DATATYPE=TEXT, ENCODING=PLAIN",
      "CREATE TIMESERIES root.vehicle.d0.s4 WITH DATATYPE=BOOLEAN, ENCODING=PLAIN",

      "CREATE TIMESERIES root.vehicle.d1.s0 WITH DATATYPE=INT32, ENCODING=RLE",

      "CREATE TIMESERIES root.other.d1.s0 WITH DATATYPE=FLOAT, ENCODING=RLE",

      "insert into root.vehicle.d0(timestamp,s0) values(1,101)",
      "insert into root.vehicle.d0(timestamp,s0) values(2,198)",
      "insert into root.vehicle.d0(timestamp,s0) values(100,99)",
      "insert into root.vehicle.d0(timestamp,s0) values(101,99)",
      "insert into root.vehicle.d0(timestamp,s0) values(102,80)",
      "insert into root.vehicle.d0(timestamp,s0) values(103,99)",
      "insert into root.vehicle.d0(timestamp,s0) values(104,90)",
      "insert into root.vehicle.d0(timestamp,s0) values(105,99)",
      "insert into root.vehicle.d0(timestamp,s0) values(106,99)",
      "insert into root.vehicle.d0(timestamp,s0) values(2,10000)",
      "insert into root.vehicle.d0(timestamp,s0) values(50,10000)",
      "insert into root.vehicle.d0(timestamp,s0) values(1000,22222)",

      "insert into root.vehicle.d0(timestamp,s1) values(1,1101)",
      "insert into root.vehicle.d0(timestamp,s1) values(2,198)",
      "insert into root.vehicle.d0(timestamp,s1) values(100,199)",
      "insert into root.vehicle.d0(timestamp,s1) values(101,199)",
      "insert into root.vehicle.d0(timestamp,s1) values(102,180)",
      "insert into root.vehicle.d0(timestamp,s1) values(103,199)",
      "insert into root.vehicle.d0(timestamp,s1) values(104,190)",
      "insert into root.vehicle.d0(timestamp,s1) values(105,199)",
      "insert into root.vehicle.d0(timestamp,s1) values(2,40000)",
      "insert into root.vehicle.d0(timestamp,s1) values(50,50000)",
      "insert into root.vehicle.d0(timestamp,s1) values(1000,55555)",

      "insert into root.vehicle.d0(timestamp,s2) values(1000,55555)",
      "insert into root.vehicle.d0(timestamp,s2) values(2,2.22)",
      "insert into root.vehicle.d0(timestamp,s2) values(3,3.33)",
      "insert into root.vehicle.d0(timestamp,s2) values(4,4.44)",
      "insert into root.vehicle.d0(timestamp,s2) values(102,10.00)",
      "insert into root.vehicle.d0(timestamp,s2) values(105,11.11)",
      "insert into root.vehicle.d0(timestamp,s2) values(1000,1000.11)",

      "insert into root.vehicle.d0(timestamp,s3) values(60,'aaaaa')",
      "insert into root.vehicle.d0(timestamp,s3) values(70,'bbbbb')",
      "insert into root.vehicle.d0(timestamp,s3) values(80,'ccccc')",
      "insert into root.vehicle.d0(timestamp,s3) values(101,'ddddd')",
      "insert into root.vehicle.d0(timestamp,s3) values(102,'fffff')",

      "insert into root.vehicle.d1(timestamp,s0) values(1,999)",
      "insert into root.vehicle.d1(timestamp,s0) values(1000,888)",

      "insert into root.vehicle.d0(timestamp,s1) values(2000-01-01T08:00:00+08:00, 100)",
      "insert into root.vehicle.d0(timestamp,s3) values(2000-01-01T08:00:00+08:00, 'good')",

      "insert into root.vehicle.d0(timestamp,s4) values(100, false)",
      "insert into root.vehicle.d0(timestamp,s4) values(100, true)",

      "insert into root.other.d1(timestamp,s0) values(2, 3.14)",};

  @BeforeClass
  public static void setUp() throws Exception {
    EnvironmentUtils.closeStatMonitor();
    daemon = IoTDB.getInstance();
    daemon.active();
    EnvironmentUtils.envSetUp();

    insertData();

  }
  
  @AfterClass
  public static void tearDown() throws Exception {
    daemon.stop();
    EnvironmentUtils.cleanEnv();
  }
  
  private static void insertData() throws ClassNotFoundException {
    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {

      for (String sql : sqls) {
        statement.execute(sql);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
  
  @Test
  public void selectTest() throws ClassNotFoundException {
    String[] retArray = new String[]{
        "1,101,1,1101,2,2.22,60,aaaaa,100,true,1,999,",
        "2,10000,2,40000,3,3.33,70,bbbbb,null,null,1000,888,",
        "50,10000,50,50000,4,4.44,80,ccccc,null,null,null,null,",
        "100,99,100,199,102,10.0,101,ddddd,null,null,null,null,",
        "101,99,101,199,105,11.11,102,fffff,null,null,null,null,",
        "102,80,102,180,1000,1000.11,946684800000,good,null,null,null,null,",
        "103,99,103,199,null,null,null,null,null,null,null,null,",
        "104,90,104,190,null,null,null,null,null,null,null,null,",
        "105,99,105,199,null,null,null,null,null,null,null,null,",
        "106,99,1000,55555,null,null,null,null,null,null,null,null,",
        "1000,22222,946684800000,100,null,null,null,null,null,null,null,null,",
    };

    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select * from root.vehicle disable align");
      Assert.assertTrue(hasResultSet);

      try (ResultSet resultSet = statement.getResultSet()) {
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
          header.append(resultSetMetaData.getColumnName(i)).append(",");
        }
        Assert.assertEquals(
            "Timeroot.vehicle.d0.s0,root.vehicle.d0.s0,"
            + "Timeroot.vehicle.d0.s1,root.vehicle.d0.s1,"
            + "Timeroot.vehicle.d0.s2,root.vehicle.d0.s2,"
            + "Timeroot.vehicle.d0.s3,root.vehicle.d0.s3,"
            + "Timeroot.vehicle.d0.s4,root.vehicle.d0.s4,"
            + "Timeroot.vehicle.d1.s0,root.vehicle.d1.s0,", header.toString());
        Assert.assertEquals(Types.TIMESTAMP, resultSetMetaData.getColumnType(1));
        Assert.assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(2));
        Assert.assertEquals(Types.BIGINT, resultSetMetaData.getColumnType(3));
        Assert.assertEquals(Types.FLOAT, resultSetMetaData.getColumnType(4));
        Assert.assertEquals(Types.VARCHAR, resultSetMetaData.getColumnType(5));
        Assert.assertEquals(Types.BOOLEAN, resultSetMetaData.getColumnType(6));
        Assert.assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(7));

        int cnt = 0;
        while (resultSet.next()) {
          StringBuilder builder = new StringBuilder();
          for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            builder.append(resultSet.getString(i)).append(",");
          }
          Assert.assertEquals(retArray[cnt], builder.toString());
          cnt++;
        }
        Assert.assertEquals(11, cnt);
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
  
  @Test
  public void selectWithDuplicatedPathsTest() throws ClassNotFoundException {
    String[] retArray = new String[]{
        "1,101,1,101,1,1101,",
        "2,10000,2,10000,2,40000,",
        "50,10000,50,10000,50,50000,",
        "100,99,100,99,100,199,",
        "101,99,101,99,101,199,",
        "102,80,102,80,102,180,",
        "103,99,103,99,103,199,",
        "104,90,104,90,104,190,",
        "105,99,105,99,105,199,",
        "106,99,106,99,1000,55555,",
        "1000,22222,1000,22222,946684800000,100,",
    };

    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select s0,s0,s1 from root.vehicle.d0 disable align");
      Assert.assertTrue(hasResultSet);

      try (ResultSet resultSet = statement.getResultSet()) {
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
          header.append(resultSetMetaData.getColumnName(i)).append(",");
        }
        Assert.assertEquals("Timeroot.vehicle.d0.s0,root.vehicle.d0.s0,"
            + "Timeroot.vehicle.d0.s0,root.vehicle.d0.s0,"
            + "Timeroot.vehicle.d0.s1,root.vehicle.d0.s1,", header.toString());
        Assert.assertEquals(Types.TIMESTAMP, resultSetMetaData.getColumnType(1));
        Assert.assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(2));
        Assert.assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(3));
        Assert.assertEquals(Types.BIGINT, resultSetMetaData.getColumnType(4));

        int cnt = 0;
        while (resultSet.next()) {
          StringBuilder builder = new StringBuilder();
          for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            builder.append(resultSet.getString(i)).append(",");
          }
          Assert.assertEquals(retArray[cnt], builder.toString());
          cnt++;
        }
        Assert.assertEquals(11, cnt);
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
  
  @Test
  public void selectLimitTest() throws ClassNotFoundException {
    String[] retArray = new String[]{
        "2,10000,1000,888,2,40000,3,3.33,70,bbbbb,null,null,",
        "50,10000,null,null,50,50000,4,4.44,80,ccccc,null,null,",
        "100,99,null,null,100,199,102,10.0,101,ddddd,null,null,",
        "101,99,null,null,101,199,105,11.11,102,fffff,null,null,",
        "102,80,null,null,102,180,1000,1000.11,946684800000,good,null,null,",
        "103,99,null,null,103,199,null,null,null,null,null,null,",
        "104,90,null,null,104,190,null,null,null,null,null,null,",
        "105,99,null,null,105,199,null,null,null,null,null,null,",
        "106,99,null,null,1000,55555,null,null,null,null,null,null,",
        "1000,22222,null,null,946684800000,100,null,null,null,null,null,null,",
    };

    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select s0,s1,s2,s3,s4 from root.vehicle.* limit 10 offset 1 disable align");
      Assert.assertTrue(hasResultSet);

      try (ResultSet resultSet = statement.getResultSet()) {
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
          header.append(resultSetMetaData.getColumnName(i)).append(",");
        }
        Assert.assertEquals(
            "Timeroot.vehicle.d0.s0,root.vehicle.d0.s0,"
            + "Timeroot.vehicle.d1.s0,root.vehicle.d1.s0,"
            + "Timeroot.vehicle.d0.s1,root.vehicle.d0.s1,"
            + "Timeroot.vehicle.d0.s2,root.vehicle.d0.s2,"
            + "Timeroot.vehicle.d0.s3,root.vehicle.d0.s3,"
            + "Timeroot.vehicle.d0.s4,root.vehicle.d0.s4,", header.toString());
        Assert.assertEquals(Types.TIMESTAMP, resultSetMetaData.getColumnType(1));
        Assert.assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(2));
        Assert.assertEquals(Types.INTEGER, resultSetMetaData.getColumnType(3));
        Assert.assertEquals(Types.BIGINT, resultSetMetaData.getColumnType(4));
        Assert.assertEquals(Types.FLOAT, resultSetMetaData.getColumnType(5));
        Assert.assertEquals(Types.VARCHAR, resultSetMetaData.getColumnType(6));
        Assert.assertEquals(Types.BOOLEAN, resultSetMetaData.getColumnType(7));

        int cnt = 0;
        while (resultSet.next()) {
          StringBuilder builder = new StringBuilder();
          for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            builder.append(resultSet.getString(i)).append(",");
          }
          Assert.assertEquals(retArray[cnt], builder.toString());
          cnt++;
        }
        Assert.assertEquals(10, cnt);
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void selectSlimitTest() throws ClassNotFoundException {
    String[] retArray = new String[]{
        "1,1101,2,2.22,",
        "2,40000,3,3.33,",
        "50,50000,4,4.44,",
        "100,199,102,10.0,",
        "101,199,105,11.11,",
        "102,180,1000,1000.11,",
        "103,199,null,null,",
        "104,190,null,null,",
        "105,199,null,null,",
        "1000,55555,null,null,",
        "946684800000,100,null,null,",
    };

    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select * from root.vehicle.* slimit 2 soffset 1 disable align");
      Assert.assertTrue(hasResultSet);

      try (ResultSet resultSet = statement.getResultSet()) {
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
          header.append(resultSetMetaData.getColumnName(i)).append(",");
        }
        Assert.assertEquals(
            "Timeroot.vehicle.d0.s1,root.vehicle.d0.s1,"
            + "Timeroot.vehicle.d0.s2,root.vehicle.d0.s2,", header.toString());
        Assert.assertEquals(Types.TIMESTAMP, resultSetMetaData.getColumnType(1));
        Assert.assertEquals(Types.BIGINT, resultSetMetaData.getColumnType(2));
        Assert.assertEquals(Types.FLOAT, resultSetMetaData.getColumnType(3));

        int cnt = 0;
        while (resultSet.next()) {
          StringBuilder builder = new StringBuilder();
          for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            builder.append(resultSet.getString(i)).append(",");
          }
          Assert.assertEquals(retArray[cnt], builder.toString());
          cnt++;
        }
        Assert.assertEquals(11, cnt);
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
  
  @Test
  public void errorCaseTest1() throws ClassNotFoundException {
    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select * from root.vehicle where time = 3 Fill(int32[previous, 5ms]) disable align");
      fail("No exception thrown.");
    } catch (Exception e) {
      Assert.assertTrue(e.getMessage().contains(
          "Fill doesn't support disable align clause."));
    }
  }

  @Test
  public void errorCaseTest2() throws ClassNotFoundException {
    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select count(*) from root.vehicle GROUP BY ([2,50],20ms) disable align");
      fail("No exception thrown.");
    } catch (Exception e) {
      Assert.assertTrue(e.getMessage().contains("Group by doesn't support disable align clause."));
    }
  }

  @Test
  public void errorCaseTest3() throws ClassNotFoundException {
    Class.forName(Config.JDBC_DRIVER_NAME);
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(
          "select count(*) from root disable align");
      fail("No exception thrown.");
    } catch (Exception e) {
      Assert.assertTrue(e.getMessage().contains(
          "Aggregation doesn't support disable align clause."));
    }
  }

}
