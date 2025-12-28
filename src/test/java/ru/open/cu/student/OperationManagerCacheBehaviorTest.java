package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperationManagerCacheBehaviorTest {

    @Test
    void repeatedInsertsUseCacheAndAvoidFullScan(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();
            DefaultOperationManager ops = new DefaultOperationManager(catalog);

            TypeDefinition varchar = catalog.getType("varchar");
            TypeDefinition integer = catalog.getType("integer");
            assertNotNull(varchar);
            assertNotNull(integer);

            String tableName = "cache_test_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "name", 0),
                            new ColumnDefinition(integer.oid(), "id", 1)
                    )
            );

            TableDefinition td = catalog.getTable(tableName);
            assertNotNull(td);
            int oid = td.getOid();

            String small = "x".repeat(20);

            // First insert: cache is empty -> should perform a scan (at least one page check)
            ops.insert(tableName, List.of(small, 1));
            int scansAfterFirst = ops.getScanCountForTable(oid);
            assertTrue(scansAfterFirst >= 1, "Expected at least one page scan on first insert");

            // Second insert: should use cached page and NOT increment scan counter
            ops.insert(tableName, List.of(small, 2));
            int scansAfterSecond = ops.getScanCountForTable(oid);
            assertEquals(scansAfterFirst, scansAfterSecond, "Second insert should not perform full scan when cache available");

            // Third insert: also should use cache
            ops.insert(tableName, List.of(small, 3));
            int scansAfterThird = ops.getScanCountForTable(oid);
            assertEquals(scansAfterFirst, scansAfterThird, "Subsequent inserts should keep using cache until it's exhausted");

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}

