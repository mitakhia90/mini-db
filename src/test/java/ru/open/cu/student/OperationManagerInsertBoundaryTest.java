package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;
import ru.open.cu.student.catalog.operation.OperationManager;
import ru.open.cu.student.memory.page.HeapPage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperationManagerInsertBoundaryTest {

    @Test
    void appendNewPageWhenFull(@TempDir Path tempDir) throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();
            OperationManager ops = new DefaultOperationManager(catalog);

            TypeDefinition varchar = catalog.getType("varchar");
            TypeDefinition integer = catalog.getType("integer");
            assertNotNull(varchar);
            assertNotNull(integer);

            String tableName = "om_test_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "name", 0),
                            new ColumnDefinition(integer.oid(), "id", 1)
                    )
            );

            Path dataPath = Path.of(catalog.getTable(tableName).getOid() + ".dat").toAbsolutePath();

            String small = "x".repeat(100);
            int inserted = 0;
            int max = 500;
            while (inserted < max) {
                ops.insert(tableName, List.of(small, inserted));
                inserted++;
                long size = Files.size(dataPath);
                if (size >= 2L * HeapPage.PAGE_SIZE) break;
            }

            assertTrue(Files.size(dataPath) >= 2L * HeapPage.PAGE_SIZE, "Data file should have at least 2 pages");

            List<Object> rows = ops.select(tableName, List.of());
            assertEquals(inserted, rows.size(), "Number of rows read should match inserted count");

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }

    @Test
    void insertTooLargeRowThrows(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();
            OperationManager ops = new DefaultOperationManager(catalog);

            TypeDefinition varchar = catalog.getType("varchar");
            assertNotNull(varchar);

            String tableName = "om_large_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "big", 0)
                    )
            );

            // Construct a string larger than page size
            String big = "a".repeat(HeapPage.PAGE_SIZE + 100);

            assertThrows(IllegalArgumentException.class, () -> ops.insert(tableName, List.of(big)));

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}

