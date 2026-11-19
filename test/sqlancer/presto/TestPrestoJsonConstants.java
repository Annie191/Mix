package sqlancer.presto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import sqlancer.Randomly;
import sqlancer.presto.ast.PrestoConstant.PrestoJsonConstant;

public class TestPrestoJsonConstants {

    @Test
    void generatedLiteralsAreStrictJson() throws Exception {
        new Randomly(2);
        for (int i = 0; i < 10000; i++) {
            PrestoJsonConstant constant = new PrestoJsonConstant();
            try (JsonReader reader = new JsonReader(new StringReader(constant.getValue()))) {
                reader.setStrictness(Strictness.STRICT);
                reader.skipValue();
                assertEquals(JsonToken.END_DOCUMENT, reader.peek(), constant.toString());
            }
        }
    }
}
