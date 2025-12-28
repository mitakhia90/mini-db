package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;
import ru.open.cu.student.catalog.operation.OperationManager;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsertNoSpaceRegressionTest {

    @Test
    void insertIntoNewTable_shouldNotFailWithNoSpaceInPage_andShouldBeReadable(@TempDir Path tempDir) {
        // DefaultOperationManager пишет в файл "{oid}.dat" в текущей директории,
        // поэтому для изоляции теста переключаем рабочую директорию.
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();
            OperationManager ops = new DefaultOperationManager(catalog);

            TypeDefinition varchar = catalog.getType("varchar");
            TypeDefinition integer = catalog.getType("integer");
            assertNotNull(varchar);
            assertNotNull(integer);

            String tableName = "test002_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "name", 0),
                            new ColumnDefinition(integer.oid(), "id", 1)
                    )
            );

            assertDoesNotThrow(() -> ops.insert(tableName, List.of("name", 0)));

            List<Object> rows = ops.select(tableName, List.of());
            assertEquals(1, rows.size());
            assertInstanceOf(List.class, rows.getFirst());
            assertEquals(List.of("name", 0), rows.getFirst());
        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
