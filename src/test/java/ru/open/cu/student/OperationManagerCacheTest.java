package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.InMemoryCatalog;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;
import ru.open.cu.student.io.CountingFileAccessor;
import ru.open.cu.student.io.DiskFileAccessor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperationManagerCacheTest {

    @Test
    void repeatedInsertsUseCacheAndReduceReads(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new InMemoryCatalog();
            CountingFileAccessor counting = new CountingFileAccessor(new DiskFileAccessor());
            DefaultOperationManager ops = new DefaultOperationManager(catalog, counting);

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

            String small = "x".repeat(20);

            // First insert
            ops.insert(tableName, List.of(small, 1));
            int readsAfterFirst = counting.getReadCount();
            assertTrue(readsAfterFirst >= 1, "Expected at least one page read on first insert");

            // Second insert: should use cached page — allow at most one additional read (write may cause a read)
            ops.insert(tableName, List.of(small, 2));
            int readsAfterSecond = counting.getReadCount();
            assertTrue(readsAfterSecond - readsAfterFirst <= 1, "Second insert should not perform a full scan when cache is available");

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
