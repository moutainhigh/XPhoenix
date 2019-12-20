package org.apache.phoenix.external.index.elasticsearch.base;

import com.google.common.collect.Lists;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.util.KeyValueBuilder;
import org.apache.phoenix.schema.*;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.util.EncodedColumnsUtil;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.SchemaUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.*;

public class IndexTestUtil {

    // the normal db metadata interface is insufficient for all fields needed for an
    // index table test.
    private static final String SELECT_DATA_INDEX_ROW = "SELECT " + COLUMN_FAMILY
            + " FROM "
            + "\""+SYSTEM_CATALOG_SCHEMA +"\""+ ".\"" + SYSTEM_CATALOG_TABLE
            + "\" WHERE "
            + TENANT_ID + " IS NULL AND " + TABLE_SCHEM + "=? AND " + TABLE_NAME + "=? AND " + COLUMN_NAME + " IS NULL AND " + COLUMN_FAMILY + "=?";

    public static ResultSet readDataTableIndexRow(Connection conn, String schemaName, String tableName, String indexName) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(SELECT_DATA_INDEX_ROW);
        stmt.setString(1, schemaName);
        stmt.setString(2, tableName);
        stmt.setString(3, indexName);
        return stmt.executeQuery();
    }


    private static void coerceDataValueToIndexValue(PColumn dataColumn, PColumn indexColumn, ImmutableBytesWritable ptr) {
        PDataType dataType = dataColumn.getDataType();
        SortOrder dataModifier = dataColumn.getSortOrder();
        PDataType indexType = indexColumn.getDataType();
        SortOrder indexModifier = indexColumn.getSortOrder();
        // We know ordinal position will match pk position, because you cannot
        // alter an index table.
        indexType.coerceBytes(ptr, dataType, dataModifier, indexModifier);
    }

    public static List<Mutation> generateIndexData(PTable index, PTable table,
                                                   List<Mutation> dataMutations, ImmutableBytesWritable ptr, KeyValueBuilder builder)
            throws SQLException {
        List<Mutation> indexMutations = Lists.newArrayListWithExpectedSize(dataMutations.size());
        for (Mutation dataMutation : dataMutations) {
            indexMutations.addAll(generateIndexData(index, table, dataMutation, ptr, builder));
        }
        return indexMutations;
    }

    public static List<Mutation> generateIndexData(PTable indexTable, PTable dataTable,
                                                   Mutation dataMutation, ImmutableBytesWritable ptr, KeyValueBuilder builder)
            throws SQLException {
        byte[] dataRowKey = dataMutation.getRow();
        RowKeySchema dataRowKeySchema = dataTable.getRowKeySchema();
        List<PColumn> dataPKColumns = dataTable.getPKColumns();
        int i = 0;
        int indexOffset = 0;
        Boolean hasValue;
        // Skip salt column
        int maxOffset = dataRowKey.length;
        dataRowKeySchema.iterator(dataRowKey, ptr, dataTable.getBucketNum() == null ? i : ++i);
        List<PColumn> indexPKColumns = indexTable.getPKColumns();
        List<PColumn> indexColumns = indexTable.getColumns();
        int nIndexColumns = indexPKColumns.size();
        int maxIndexValues = indexColumns.size() - nIndexColumns - indexOffset;
        BitSet indexValuesSet = new BitSet(maxIndexValues);
        byte[][] indexValues = new byte[indexColumns.size() - indexOffset][];
        while ((hasValue = dataRowKeySchema.next(ptr, i, maxOffset)) != null) {
            if (hasValue) {
                PColumn dataColumn = dataPKColumns.get(i);
                PColumn indexColumn = indexTable.getColumnForColumnName(IndexUtil.getIndexColumnName(dataColumn));
                coerceDataValueToIndexValue(dataColumn, indexColumn, ptr);
                indexValues[indexColumn.getPosition()-indexOffset] = ptr.copyBytes();
            }
            i++;
        }
        PRow row;
        long ts = MetaDataUtil.getClientTimeStamp(dataMutation);
        if (dataMutation instanceof Delete && dataMutation.getFamilyCellMap().values().isEmpty()) {
            indexTable.newKey(ptr, indexValues);
            row = indexTable.newRow(builder, ts, ptr, false);
            row.delete();
        } else {
            // If no column families in table, then nothing to look for
            if (!dataTable.getColumnFamilies().isEmpty()) {
                for (Map.Entry<byte[],List<Cell>> entry : dataMutation.getFamilyCellMap().entrySet()) {
                    PColumnFamily family = dataTable.getColumnFamily(entry.getKey());
                    for (Cell kv : entry.getValue()) {
                        @SuppressWarnings("deprecation")
                        byte[] cq = kv.getQualifier();
                        byte[] emptyKVQualifier = EncodedColumnsUtil.getEmptyKeyValueInfo(dataTable).getFirst();
                        if (Bytes.compareTo(emptyKVQualifier, cq) != 0) {
                            try {
                                PColumn dataColumn = family.getPColumnForColumnQualifier(cq);
                                PColumn indexColumn = indexTable.getColumnForColumnName(IndexUtil.getIndexColumnName(family.getName().getString(), dataColumn.getName().getString()));
                                ptr.set(kv.getValueArray(),kv.getValueOffset(),kv.getValueLength());
                                coerceDataValueToIndexValue(dataColumn, indexColumn, ptr);
                                indexValues[indexPKColumns.indexOf(indexColumn)-indexOffset] = ptr.copyBytes();
                                if (!SchemaUtil.isPKColumn(indexColumn)) {
                                    indexValuesSet.set(indexColumn.getPosition()-nIndexColumns-indexOffset);
                                }
                            } catch (ColumnNotFoundException e) {
                                // Ignore as this means that the data column isn't in the index
                            }
                        }
                    }
                }
            }
            indexTable.newKey(ptr, indexValues);
            row = indexTable.newRow(builder, ts, ptr, false);
            int pos = 0;
            while ((pos = indexValuesSet.nextSetBit(pos)) >= 0) {
                int index = nIndexColumns + indexOffset + pos++;
                PColumn indexColumn = indexColumns.get(index);
                row.setValue(indexColumn, indexValues[index]);
            }
        }
        return row.toRowMutations();
    }

}