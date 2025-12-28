package ru.open.cu.student.catalog.operation;

import java.util.List;

public interface OperationManager {

    void insert(String tableName, List<Object> values);

    List<Object> select(String tableName, List<String> columnNames);

    // Удалить строки из таблицы, где columnName = value
    default int delete(String tableName, String columnName, Object value) {
        throw new UnsupportedOperationException("delete is not supported");
    }

}
