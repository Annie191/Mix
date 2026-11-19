package sqlancer.presto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

public class TestPrestoJdbcResults {

    @Test
    void jdbcDecodesStringEncodedIntervalArrayFromRecordedServerResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String response = "{\"id\":\"ci_array_result\",\"infoUri\":\"http://localhost/query\","
                + "\"columns\":[{\"name\":\"_col0\",\"type\":\"array(interval year to month)\","
                + "\"typeSignature\":{\"rawType\":\"array\",\"typeArguments\":[{\"rawType\":\"interval year to month\","
                + "\"typeArguments\":[],\"literalArguments\":[],\"arguments\":[]}],\"literalArguments\":[],"
                + "\"arguments\":[{\"kind\":\"TYPE_SIGNATURE\",\"value\":{\"rawType\":\"interval year to month\","
                + "\"typeArguments\":[],\"literalArguments\":[],\"arguments\":[]}}]}}],"
                + "\"data\":[[\"[ \\\"1983-0\\\" ]\"]],"
                + "\"stats\":{\"state\":\"FINISHED\",\"waitingForPrerequisites\":false,\"queued\":false,"
                + "\"scheduled\":true,\"nodes\":1,\"totalSplits\":1,\"queuedSplits\":0,\"runningSplits\":0,"
                + "\"completedSplits\":1,\"cpuTimeMillis\":1,\"wallTimeMillis\":1,\"processedRows\":1,"
                + "\"processedBytes\":0},\"warnings\":[]}";
        server.createContext("/v1/statement", exchange -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try (Connection connection = DriverManager
                .getConnection("jdbc:presto://127.0.0.1:" + server.getAddress().getPort(), "presto", null);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT MIN(ARRAY[INTERVAL '1983' YEAR])")) {
            assertTrue(result.next());
            Object[] elements = (Object[]) result.getArray(1).getArray();
            assertEquals(1, elements.length);
            assertEquals("1983-0", elements[0].toString());
            assertFalse(result.next());
        } finally {
            server.stop(0);
        }
    }
}
