package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DropTableTest {

    @Test
    void dropRemovesTableAndDeletesDataFile(@TempDir Path tempDir) throws Exception {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();

            TypeDefinition varchar = catalog.getType("varchar");
            TypeDefinition integer = catalog.getType("integer");
            assertNotNull(varchar);
            assertNotNull(integer);

            String tableName = "drop_test_" + Math.abs(tempDir.toAbsolutePath().toString().hashCode());

            var tbl = catalog.createTable(
                    tableName,
                    List.of(
                            new ColumnDefinition(varchar.oid(), "name", 0),
                            new ColumnDefinition(integer.oid(), "id", 1)
                    )
            );

            Path dataPath = Path.of(tbl.getFileNode()).toAbsolutePath();
            assertTrue(Files.exists(dataPath), "Data file should exist after create");

            // Drop
            catalog.dropTable(tableName);

            assertNull(catalog.getTable(tableName), "Table should be removed from catalog");
            assertFalse(Files.exists(dataPath), "Data file should be deleted after drop");

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }

    @Test
    void dropNonExistingThrows(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            CatalogManager catalog = new DefaultCatalogManager();

            assertThrows(IllegalArgumentException.class, () -> catalog.dropTable("no_such_table"));

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }

    @Test
    void dropIfExistsDoesNotThrow(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            var engine = new ru.open.cu.student.cli.impl.DefaultEngine();

            // dropping non-existing table with IF EXISTS should return OK
            String resp = engine.executeSql("drop table if exists some_nonexists;");
            assertEquals("OK", resp);

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
