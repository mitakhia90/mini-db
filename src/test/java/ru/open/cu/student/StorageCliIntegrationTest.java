package ru.open.cu.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.open.cu.student.cli.impl.DefaultEngine;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class StorageCliIntegrationTest {

    @Test
    void createInsertSelect_shouldWork(@TempDir Path tempDir) {
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        try {
            DefaultEngine engine = new DefaultEngine();

            String tableName = "test_cli_" + UUID.randomUUID().toString().replace("-", "_");

            String r1 = engine.executeSql("create table " + tableName + "(name varchar, id int);");
            assertEquals("OK", r1, "CREATE should succeed, got: " + r1);

            String r2 = engine.executeSql("insert into " + tableName + " values('name', 0);");
            assertEquals("OK", r2, "INSERT should succeed, got: " + r2);

            // enable debug flag to include rows dump in returned string
            System.setProperty("debug.engine", "1");
            String r3 = engine.executeSql("select * from " + tableName + ";");
            System.clearProperty("debug.engine");

            assertFalse(r3.startsWith("ERROR:"), () -> "SELECT returned error: " + r3);
            assertTrue(r3.contains("name"), () -> "SELECT output should contain inserted name, was: " + r3);

        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
