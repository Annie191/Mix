package sqlancer.dbms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.mysql.gen.MySQLExpressionGenerator;

public class TestMySQLTransactions {

    @Test
    void rollbackUndoesWritesAndKeepsConnectionForEveryCompletionType() throws Exception {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.MYSQL_ENV));
        MainOptions options = new MainOptions();
        String url = "jdbc:mysql://localhost:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, options.getUserName(), options.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE rollback_guard (v INT) ENGINE=InnoDB");
            statement.execute("INSERT INTO rollback_guard VALUES (1)");
            for (int completionType = 0; completionType <= 2; completionType++) {
                statement.execute("SET SESSION completion_type=" + completionType);
                statement.execute("BEGIN");
                statement.execute("UPDATE rollback_guard SET v=2");
                statement.execute(new MySQLExpressionGenerator(null).rollbackTransactionStatement());
                try (ResultSet result = statement.executeQuery("SELECT v FROM rollback_guard")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
