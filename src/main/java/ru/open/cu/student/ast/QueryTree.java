package ru.open.cu.student.ast;

import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.parser.nodes.RangeVar;
import java.util.*;

public class QueryTree {
    public List<RangeVar> rangeTable;        // таблицы, участвующие в запросе
    public List<TargetEntry> targetList;     // что выбираем
    public Expr whereClause;                 // условие отбора
    public QueryType commandType;            // SELECT, INSERT, etc.
    public boolean dropIfExists;

    // Дополнительные поля для INSERT/CREATE TABLE
    public String tableName;
    public List<String> columnNames;
    public List<Object> values;

    // Для хранения семантической информации
    private Map<String, TableInfo> tableInfoMap = new HashMap<>();
    private Map<String, ColumnInfo> columnInfoMap = new HashMap<>();

    // Вложенные классы для информации
    public static class TableInfo {
        public final String name;
        public final String alias;
        public final List<ColumnInfo> columns;

        public TableInfo(String name, String alias, List<ColumnInfo> columns) {
            this.name = name;
            this.alias = alias;
            this.columns = columns;
        }
    }

    public static class ColumnInfo {
        public final String tableName;
        public final String columnName;
        public final TypeDefinition type;
        public final int tableIndex;
        public final int columnIndex;

        public ColumnInfo(String tableName, String columnName, TypeDefinition type,
                          int tableIndex, int columnIndex) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.type = type;
            this.tableIndex = tableIndex;
            this.columnIndex = columnIndex;
        }
    }

    public QueryTree() {
        this.rangeTable = new ArrayList<>();
        this.targetList = new ArrayList<>();
    }

    // Геттеры и сеттеры
    public List<RangeVar> getRangeTable() { return rangeTable; }
    public void setRangeTable(List<RangeVar> rangeTable) { this.rangeTable = rangeTable; }
    public void addRangeVar(RangeVar rangeVar) { rangeTable.add(rangeVar); }

    public List<TargetEntry> getTargetList() { return targetList; }
    public void setTargetList(List<TargetEntry> targetList) { this.targetList = targetList; }
    public void addTargetEntry(TargetEntry entry) { targetList.add(entry); }

    public Expr getWhereClause() { return whereClause; }
    public void setWhereClause(Expr whereClause) { this.whereClause = whereClause; }

    public QueryType getCommandType() { return commandType; }
    public void setCommandType(QueryType commandType) { this.commandType = commandType; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public List<String> getColumnNames() { return columnNames; }
    public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }

    public List<Object> getValues() { return values; }
    public void setValues(List<Object> values) { this.values = values; }

    public Map<String, TableInfo> getTableInfoMap() { return tableInfoMap; }
    public void addTableInfo(String key, TableInfo info) { tableInfoMap.put(key, info); }

    public Map<String, ColumnInfo> getColumnInfoMap() { return columnInfoMap; }
    public void addColumnInfo(String key, ColumnInfo info) { columnInfoMap.put(key, info); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("QueryTree {\n");
        sb.append("  commandType: ").append(commandType).append("\n");

        if (tableName != null) {
            sb.append("  table: ").append(tableName).append("\n");
        }

        if (!targetList.isEmpty()) {
            sb.append("  targetList: ").append(targetList).append("\n");
        }

        if (!rangeTable.isEmpty()) {
            sb.append("  rangeTable: ").append(rangeTable).append("\n");
        }

        if (whereClause != null) {
            sb.append("  where: ").append(whereClause).append("\n");
        }

        sb.append("}");
        return sb.toString();
    }
}