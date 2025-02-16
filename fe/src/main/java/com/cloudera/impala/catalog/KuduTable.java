// Copyright 2015 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.bind.DatatypeConverter;

import com.cloudera.impala.analysis.AlterTableOrViewRenameStmt;
import com.cloudera.impala.analysis.AlterTableSetTblProperties;
import com.cloudera.impala.analysis.AlterTableStmt;
import com.cloudera.impala.common.ImpalaRuntimeException;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.cloudera.impala.thrift.TColumn;
import com.cloudera.impala.thrift.TKuduTable;
import com.cloudera.impala.thrift.TResultSet;
import com.cloudera.impala.thrift.TResultSetMetadata;
import com.cloudera.impala.thrift.TTable;
import com.cloudera.impala.thrift.TTableDescriptor;
import com.cloudera.impala.thrift.TTableType;
import com.cloudera.impala.util.TResultRowBuilder;
import com.cloudera.impala.util.KuduUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.log4j.Logger;
import org.kududb.client.KuduClient;
import org.kududb.client.LocatedTablet;

/**
 * Impala representation of a Kudu table.
 *
 * The Kudu-related metadata is stored in the Metastore table's table properties.
 */
public class KuduTable extends Table {
  private static final Logger LOG = Logger.getLogger(Table.class);

  // Alias to the string key that identifies the storage handler for Kudu tables.
  public static final String KEY_STORAGE_HANDLER =
      hive_metastoreConstants.META_TABLE_STORAGE;

  // Key to access the table name from the table properties
  public static final String KEY_TABLE_NAME = "kudu.table_name";

  // Key to access the columns used to build the (composite) key of the table.
  // The order of the keys is important.
  public static final String KEY_KEY_COLUMNS = "kudu.key_columns";

  // Key to access the master address from the table properties. Error handling for
  // this string is done in the KuduClient library.
  // TODO we should have something like KuduConfig.getDefaultConfig()
  public static final String KEY_MASTER_ADDRESSES = "kudu.master_addresses";

  // Kudu specific value for the storage handler table property keyed by
  // KEY_STORAGE_HANDLER.
  public static final String KUDU_STORAGE_HANDLER =
      "com.cloudera.kudu.hive.KuduStorageHandler";

  // Key to specify the number of tablet replicas.
  // TODO(KUDU): Allow modification in alter table.
  public static final String KEY_TABLET_REPLICAS = "kudu.num_tablet_replicas";

  public static final long KUDU_RPC_TIMEOUT_MS = 50000;

  // The name of the table in Kudu.
  private String kuduTableName_;

  // Comma separated list of Kudu master hosts with optional ports.
  private String kuduMasters_;

  // The set of columns that are key columns in Kudu.
  private ImmutableList<String> kuduKeyColumnNames_;

  public static boolean alterTableAllowed(AlterTableStmt stmt) {
    return stmt instanceof AlterTableSetTblProperties ||
        stmt instanceof AlterTableOrViewRenameStmt;
  }

  protected KuduTable(TableId id, org.apache.hadoop.hive.metastore.api.Table msTable,
      Db db, String name, String owner) {
    super(id, msTable, db, name, owner);
  }

  public TKuduTable getKuduTable() {
    TKuduTable tbl = new TKuduTable();
    tbl.setKey_columns(Preconditions.checkNotNull(kuduKeyColumnNames_));
    tbl.setMaster_addresses(Lists.newArrayList(kuduMasters_.split(",")));
    tbl.setTable_name(kuduTableName_);
    return tbl;
  }

  @Override
  public TTableDescriptor toThriftDescriptor(Set<Long> referencedPartitions) {
    TTableDescriptor desc = new TTableDescriptor(id_.asInt(), TTableType.KUDU_TABLE,
        getTColumnDescriptors(), numClusteringCols_, kuduTableName_, db_.getName());
    desc.setKuduTable(getKuduTable());
    return desc;
  }

  @Override
  public TCatalogObjectType getCatalogObjectType() { return TCatalogObjectType.TABLE; }

  @Override
  public String getStorageHandlerClassName() { return KUDU_STORAGE_HANDLER; }

  /**
   * Returns the columns in the order they have been created
   */
  @Override
  public ArrayList<Column> getColumnsInHiveOrder() { return getColumns(); }

  public static boolean isKuduTable(org.apache.hadoop.hive.metastore.api.Table mstbl) {
    return KUDU_STORAGE_HANDLER.equals(mstbl.getParameters().get(KEY_STORAGE_HANDLER));
  }

  /**
   * Load the columns from the schema list
   */
  private void loadColumns(List<FieldSchema> schema, HiveMetaStoreClient client,
      Set<String> keyColumns) throws TableLoadingException {

    if (keyColumns.size() == 0 || keyColumns.size() > schema.size()) {
      throw new TableLoadingException(String.format("Kudu tables must have at least one"
          + "key column (had %d), and no more key columns than there are table columns "
          + "(had %d).", keyColumns.size(), schema.size()));
    }

    Set<String> columnNames = Sets.newHashSet();
    int pos = 0;
    for (FieldSchema field: schema) {
      com.cloudera.impala.catalog.Type type = parseColumnType(field);
      // TODO(kudu-merge): Check for decimal types?
      boolean isKey = keyColumns.contains(field.getName());
      KuduColumn col = new KuduColumn(field.getName(), isKey, !isKey, type,
          field.getComment(), pos);
      columnNames.add(col.getName());
      addColumn(col);
      ++pos;
    }

    if (!columnNames.containsAll(keyColumns)) {
      throw new TableLoadingException(String.format("Some key columns were not found in"
              + " the set of columns. List of column names: %s, List of key column names:"
              + " %s", Iterables.toString(columnNames), Iterables.toString(keyColumns)));
    }

    kuduKeyColumnNames_ = ImmutableList.copyOf(keyColumns);

    loadAllColumnStats(client);
  }

  @Override
  public void load(boolean reuseMetadata, HiveMetaStoreClient client,
      org.apache.hadoop.hive.metastore.api.Table msTbl) throws TableLoadingException {
    // TODO handle 'reuseMetadata'
    if (getMetaStoreTable() == null || !tableParamsAreValid(msTbl.getParameters())) {
      throw new TableLoadingException(String.format(
          "Cannot load Kudu table %s, table is corrupt.", name_));
    }

    msTable_ = msTbl;
    kuduTableName_ = msTbl.getParameters().get(KEY_TABLE_NAME);
    kuduMasters_ = msTbl.getParameters().get(KEY_MASTER_ADDRESSES);

    String keyColumnsProp = Preconditions.checkNotNull(msTbl.getParameters()
        .get(KEY_KEY_COLUMNS).toLowerCase(), "'kudu.key_columns' cannot be null.");
    Set<String> keyColumns = KuduUtil.parseKeyColumns(keyColumnsProp);

    // Load the rest of the data from the table parameters directly
    loadColumns(msTbl.getSd().getCols(), client, keyColumns);

    numClusteringCols_ = 0;

    // Get row count from stats
    numRows_ = getRowCount(getMetaStoreTable().getParameters());
  }

  @Override
  public TTable toThrift() {
    TTable table = super.toThrift();
    table.setTable_type(TTableType.KUDU_TABLE);
    table.setKudu_table(getKuduTable());
    return table;
  }

  @Override
  protected void loadFromThrift(TTable thriftTable) throws TableLoadingException {
    super.loadFromThrift(thriftTable);
    TKuduTable tkudu = thriftTable.getKudu_table();
    kuduTableName_ = tkudu.getTable_name();
    kuduMasters_ = Joiner.on(',').join(tkudu.getMaster_addresses());
    kuduKeyColumnNames_ = ImmutableList.copyOf(tkudu.getKey_columns());
  }

  public String getKuduTableName() { return kuduTableName_; }
  public String getKuduMasterAddresses() { return kuduMasters_; }
  public int getNumKeyColumns() { return kuduKeyColumnNames_.size(); }

  /**
   * Returns true if all required parameters are present in the given table properties
   * map.
   * TODO(kudu-merge) Return a more specific error string.
   */
  public static boolean tableParamsAreValid(Map<String, String> params) {
    return params.get(KEY_TABLE_NAME) != null && params.get(KEY_TABLE_NAME).length() > 0
        && params.get(KEY_MASTER_ADDRESSES) != null
        && params.get(KEY_MASTER_ADDRESSES).length() > 0
        && params.get(KEY_KEY_COLUMNS) != null
        && params.get(KEY_KEY_COLUMNS).length() > 0;
   }

  /**
   * The number of nodes is not know ahead of time and will be updated during computeStats
   * in the scan node.
   */
  public int getNumNodes() { return -1; }

  public List<String> getKuduKeyColumnNames() { return kuduKeyColumnNames_; }

  public TResultSet getTableStats() throws ImpalaRuntimeException {
    TResultSet result = new TResultSet();
    TResultSetMetadata resultSchema = new TResultSetMetadata();
    result.setSchema(resultSchema);

    resultSchema.addToColumns(new TColumn("# Rows", Type.INT.toThrift()));
    resultSchema.addToColumns(new TColumn("Start Key", Type.STRING.toThrift()));
    resultSchema.addToColumns(new TColumn("Stop Key", Type.STRING.toThrift()));
    resultSchema.addToColumns(new TColumn("Leader Replica", Type.STRING.toThrift()));
    resultSchema.addToColumns(new TColumn("# Replicas", Type.INT.toThrift()));

    try (KuduClient client = new KuduClient.KuduClientBuilder(
        getKuduMasterAddresses()).build()) {
      org.kududb.client.KuduTable kuduTable = client.openTable(kuduTableName_);
      List<LocatedTablet> tablets =
          kuduTable.getTabletsLocations(KUDU_RPC_TIMEOUT_MS);
      for (LocatedTablet tab: tablets) {
        TResultRowBuilder builder = new TResultRowBuilder();
        // TODO(kudu-merge) why -1?
        builder.add("-1");
        builder.add(DatatypeConverter.printHexBinary(
            tab.getPartition().getPartitionKeyStart()));
        builder.add(DatatypeConverter.printHexBinary(
            tab.getPartition().getPartitionKeyEnd()));
        LocatedTablet.Replica leader = tab.getLeaderReplica();
        if (leader == null) {
          // Leader might be null, if it is not yet available (e.g. during
          // leader election in Kudu)
          builder.add("Leader n/a");
        } else {
          builder.add(leader.getRpcHost() + ":" + leader.getRpcPort().toString());
        }
        builder.add(tab.getReplicas().size());
        result.addToRows(builder.get());
      }

    } catch (Exception e) {
      throw new ImpalaRuntimeException("Could not communicate with Kudu.", e);
    }
    return result;
  }
}
