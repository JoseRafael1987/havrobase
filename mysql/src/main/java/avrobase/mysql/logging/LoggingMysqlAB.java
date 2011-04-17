package avrobase.mysql.logging;

import avrobase.AvroBaseException;
import avrobase.AvroFormat;
import avrobase.Row;
import avrobase.mysql.KeyStrategy;
import avrobase.mysql.MysqlAB;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import javax.sql.DataSource;
import java.awt.image.Kernel;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This needs multiserver synchronization if you don't want to federate queries across log tables.
 * <p/>
 * User: sam
 * Date: 4/14/11
 * Time: 11:17 PM
 */
public class LoggingMysqlAB<T extends SpecificRecord, K> extends MysqlAB<T, K> {
  private String logTableName;
  private AtomicInteger count;
  private ReadWriteLock lock = new ReentrantReadWriteLock();

  public LoggingMysqlAB(ExecutorService es, DataSource datasource, String table, String family, String schemaTable, Schema schema, AvroFormat storageFormat, KeyStrategy<K> keytx) throws AvroBaseException {
    super(es, datasource, table, family, schemaTable, schema, storageFormat, keytx);
    try {
      roll();
    } catch (SQLException e) {
      throw new AvroBaseException("Could not roll log table", e);
    }
  }

  public long roll() throws SQLException {
    Lock writeLock = lock.writeLock();
    writeLock.lock();
    try {
      long id = System.currentTimeMillis() / 1000;
      logTableName = mysqlTableName + "_" + id;
      Connection connection = datasource.getConnection();
      DatabaseMetaData data = connection.getMetaData();
      {
        ResultSet tables = data.getTables(null, null, logTableName, null);
        if (!tables.next()) {
          // Create the table
          Statement statement = connection.createStatement();
          statement.executeUpdate("CREATE TABLE " + logTableName + " ( row varbinary(256) primary key, schema_id integer not null, version integer not null, format tinyint not null, avro mediumblob not null ) ENGINE=INNODB");
          statement.close();
        }
        tables.close();
      }
      connection.close();
      count = new AtomicInteger(0);
      return id;
    } finally {
      writeLock.unlock();
    }
  }

  public int count() {
    return count.get();
  }

  public String name() {
    return mysqlTableName;
  }

  @Override
  protected void log(final byte[] row, final Integer schemaId, final int format, final byte[] serialized, final long version) {
    es.submit(new Runnable() {
      @Override
      public void run() {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
          count.getAndAdd(new Insert(datasource, "INSERT INTO " + logTableName + " (row, schema_id, version, format, avro) VALUES (?,?,?,?,?)") {
            public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
              ps.setBytes(1, row);
              ps.setInt(2, schemaId);
              ps.setLong(3, version);
              ps.setInt(4, format);
              ps.setBytes(5, serialized);
            }
          }.insert());
        } finally {
          readLock.unlock();
        }
      }
    });
  }

  public Iterable<Row<T, K>> versions(final K row) {
    Connection connection = null;
    try {
      connection = datasource.getConnection();
      DatabaseMetaData metaData = connection.getMetaData();
      String name = mysqlTableName;
      ResultSet tables = metaData.getTables(null, null, name + "_%", null);
      final Pattern tablePattern = Pattern.compile(name + "_([0-9]+)");
      List<String> tableNames = new ArrayList<String>();
      while (tables.next()) {
        String tableName = tables.getString(3);
        Matcher matcher = tablePattern.matcher(tableName);
        if (matcher.matches()) {
          tableNames.add(tableName);
        }
      }
      Collections.sort(tableNames, new Comparator<String>() {
        @Override
        public int compare(String s, String s1) {
          Matcher matcher = tablePattern.matcher(s);
          matcher.matches();
          long t = Long.parseLong(matcher.group(1));
          Matcher matcher1 = tablePattern.matcher(s1);
          matcher1.matches();
          long t1 = Long.parseLong(matcher1.group(1));
          return (int)(t1 - t);
        }
      });
      for (String tableName : tableNames) {
        new Query<Iterable<Row<T, K>>>(datasource, "SELECT schema_id, version, format, avro FROM " + tableName + " WHERE row = ? ORDER BY version DESC") {

          @Override
          public void setup(PreparedStatement ps) throws AvroBaseException, SQLException {
            ps.setBytes(1, keytx.toBytes(row));
          }

          @Override
          public Iterable<Row<T, K>> execute(final ResultSet rs) throws AvroBaseException, SQLException {
            return new Iterable<Row<T, K>>() {
              @Override
              public Iterator<Row<T, K>> iterator() {
                return new Iterator<Row<T, K>>() {
                  boolean nexted;
                  boolean next;

                  @Override
                  public boolean hasNext() {
                    if (nexted) return next;
                    try {
                      nexted = true;
                      return next = rs.next();
                    } catch (SQLException e) {
                      throw new AvroBaseException(e);
                    }
                  }

                  @Override
                  public Row<T, K> next() {
                    if (!nexted) hasNext();
                    if (next) {
                      try {
                        int schemaId = rs.getInt(1);
                        long version = rs.getLong(2);
                        int format = rs.getInt(3);
                        byte[] bytes = rs.getBytes(4);

                        return new Row<T, K>(readValue(bytes, getSchema(schemaId), AvroFormat.values()[format]), row, version);
                      } catch (SQLException e) {
                        throw new AvroBaseException(e);
                      }
                    }
                    throw new NoSuchElementException();
                  }

                  @Override
                  public void remove() {
                  }
                };
              }
            };
          }
        }.query();
      }

    } catch (Exception e) {
      throw new AvroBaseException(e);
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException e) {
          // closing anyway
        }
      }
    }
    return null;
  }
}