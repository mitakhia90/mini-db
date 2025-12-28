package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.operation.DefaultOperationManager;
import ru.open.cu.student.io.CountingFileAccessor;
import ru.open.cu.student.io.DiskFileAccessor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperationManagerCacheCountingTest {

    @Test
    void repeatedInsertsUseCache_CountingAccessor(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();
            // используем конструктор с делегатом DiskFileAccessor
            CountingFileAccessor cfa = new CountingFileAccessor(new DiskFileAccessor());
            DefaultOperationManager ops = new DefaultOperationManager(catalog, cfa);

            TypeDefinition varchar = catalog.getType("varchar");
            TypeDefinition integer = catalog.getType("integer");
            assertNotNull(varchar);
            assertNotNull(integer);

            String tableName = "cache_count_test_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "name", 0),
                            new ColumnDefinition(integer.oid(), "id", 1)
                    )
            );

            String small = "x".repeat(20);

            // Baseline reads before any operation
            int before = cfa.getReadCount();

            // First insert: will need to read at least one page
            ops.insert(tableName, List.of(small, 1));
            int afterFirst = cfa.getReadCount();
            int firstReads = afterFirst - before;
            assertTrue(firstReads >= 1, "Expected at least one read on first insert");

            // Second insert: should use cache and not significantly increase reads
            ops.insert(tableName, List.of(small, 2));
            int afterSecond = cfa.getReadCount();
            int secondReads = afterSecond - afterFirst;
            assertEquals(0, secondReads, "Second insert should not increase readPageCount when cache valid");

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
