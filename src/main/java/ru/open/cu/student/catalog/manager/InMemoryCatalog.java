package ru.open.cu.student.catalog.manager;

import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class InMemoryCatalog implements CatalogManager {
    private final AtomicInteger nextTableOid = new AtomicInteger(1);
    private final AtomicInteger nextColumnOid = new AtomicInteger(1);
    private final AtomicInteger nextTypeOid = new AtomicInteger(1);

    private final Map<Integer, TableDefinition> tables = new HashMap<>();
    private final Map<Integer, List<ColumnDefinition>> tableColumns = new HashMap<>();
    private final Map<Integer, TypeDefinition> types = new HashMap<>();
    private final Map<String, Integer> tableNameToOid = new HashMap<>();

    public InMemoryCatalog() {
        initializeDefaultTypes();
    }

    @Override
    public TableDefinition createTable(String name, List<ColumnDefinition> columns) {
        if (tableNameToOid.containsKey(name.toLowerCase())) {
            throw new IllegalArgumentException("Table already exists: " + name);
        }

        int tableOid = nextTableOid.getAndIncrement();
        TableDefinition table = new TableDefinition(tableOid, name, "table", tableOid + ".dat", 0);

        tables.put(tableOid, table);
        tableNameToOid.put(name.toLowerCase(), tableOid);

        List<ColumnDefinition> cols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition c = columns.get(i);
            ColumnDefinition newCol = new ColumnDefinition(nextColumnOid.getAndIncrement(), tableOid, c.getTypeOid(), c.getName(), i);
            cols.add(newCol);
        }
        tableColumns.put(tableOid, cols);
        return table;
    }

    @Override
    public TableDefinition getTable(String tableName) {
        Integer oid = tableNameToOid.get(tableName.toLowerCase());
        return oid == null ? null : tables.get(oid);
    }

    @Override
    public ColumnDefinition getColumn(TableDefinition table, String columnName) {
        if (table == null) return null;
        List<ColumnDefinition> cols = tableColumns.get(table.getOid());
        if (cols == null) return null;
        for (ColumnDefinition c : cols) {
            if (c.getName().equalsIgnoreCase(columnName)) return c;
        }
        return null;
    }

    @Override
    public List<TableDefinition> listTables() {
        return tables.values().stream().sorted(Comparator.comparing(TableDefinition::getName)).collect(Collectors.toList());
    }

    @Override
    public TypeDefinition getType(String resultType) {
        for (TypeDefinition t : types.values()) {
            if (t.name().equalsIgnoreCase(resultType)) return t;
        }
        return null;
    }

    @Override
    public TypeDefinition getType(int resultType) {
        return types.get(resultType);
    }

    @Override
    public List<ColumnDefinition> getTableColumns(TableDefinition tableDef) {
        return tableDef == null ? Collections.emptyList() : tableColumns.get(tableDef.getOid());
    }

    @Override
    public void dropTable(String tableName) {
        if (tableName == null) return;
        Integer oid = tableNameToOid.remove(tableName.toLowerCase());
        if (oid == null) throw new IllegalArgumentException("Table not found: " + tableName);
        tables.remove(oid);
        tableColumns.remove(oid);
    }

    private void initializeDefaultTypes() {
        createTypeIfMissing(23, "integer", 4);
        createTypeIfMissing(25, "varchar", -1);
        createTypeIfMissing(16, "boolean", 1);
        createTypeIfMissing(20, "bigint", 8);
    }

    private void createTypeIfMissing(int oid, String name, int byteLength) {
        if (!types.containsKey(oid)) {
            TypeDefinition td = new TypeDefinition(oid, name, byteLength);
            types.put(oid, td);
            nextTypeOid.updateAndGet(current -> Math.max(current, oid + 1));
        }
    }
}

