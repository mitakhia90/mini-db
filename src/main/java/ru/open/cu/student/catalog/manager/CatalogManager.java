package ru.open.cu.student.catalog.manager;

import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;

import java.util.List;

public interface CatalogManager {

    TableDefinition createTable(String name, List<ColumnDefinition> columns);

    TableDefinition getTable(String tableName);

    ColumnDefinition getColumn(TableDefinition table, String columnName);

    List<TableDefinition> listTables();

    TypeDefinition getType(String resultType);
    TypeDefinition getType(int resultType);

    List<ColumnDefinition> getTableColumns(TableDefinition tableDef);

    // Drop table by name — removes table from catalog and deletes data file if exists
    void dropTable(String tableName);
}
