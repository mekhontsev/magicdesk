package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class ShellAppFunctionGatewayTest {
    @Test
    public void explicitPropertiesPreserveNamesThatAreMetadataOnlyInTheEnvelope()
            throws Exception {
        final JSONObject properties = new JSONObject()
                .put("id", "business-id").put("namespace", "business-namespace")
                .put("schemaType", "business-schema").put("properties", "business-value");
        final JSONObject envelope = new JSONObject().put("id", "document-id")
                .put("schemaType", "Document").put("properties", properties);
        final JSONObject parsed = ShellAppFunctionGateway.documentProperties(envelope);
        assertEquals(4, parsed.length());
        assertEquals("business-id", parsed.getString("id"));
        assertEquals("business-namespace", parsed.getString("namespace"));
        assertEquals("business-schema", parsed.getString("schemaType"));
        assertEquals("business-value", parsed.getString("properties"));
    }

    @Test
    public void flatDocumentsExcludeOnlyTheirOwnMetadataAndRejectMalformedEnvelopes()
            throws Exception {
        final JSONObject flat = new JSONObject().put("id", "document-id")
                .put("namespace", "document-namespace").put("schemaType", "Document")
                .put("name", "value");
        final JSONObject parsed = ShellAppFunctionGateway.documentProperties(flat);
        assertEquals(1, parsed.length());
        assertEquals("value", parsed.getString("name"));
        assertEquals(4, flat.length());
        for (final Object value : new Object[] {"{}", 1, true, JSONObject.NULL, new JSONArray()}) {
            final JSONObject envelope = new JSONObject().put("properties", value);
            assertThrows(IllegalArgumentException.class,
                    () -> ShellAppFunctionGateway.documentProperties(envelope));
        }
    }

    @Test
    public void mixedNumbersAreRejectedInEitherOrderWithoutTruncation() throws Exception {
        for (final JSONArray values : new JSONArray[] {
                new JSONArray("[1,1.5]"), new JSONArray("[1.5,1]"),
                new JSONArray().put(1).put(2.0), new JSONArray().put(2.0).put(1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ShellAppFunctionGateway.arrayType(values));
        }
    }

    @Test
    public void preservesHomogeneousAndroidDocumentPropertyTypes() throws Exception {
        assertEquals(ShellAppFunctionGateway.ArrayType.EMPTY,
                ShellAppFunctionGateway.arrayType(new JSONArray()));
        assertEquals(ShellAppFunctionGateway.ArrayType.LONG,
                ShellAppFunctionGateway.arrayType(new JSONArray().put(1).put(Long.MAX_VALUE)));
        assertEquals(ShellAppFunctionGateway.ArrayType.DOUBLE,
                ShellAppFunctionGateway.arrayType(new JSONArray().put(1.5).put(2.5)));
        assertEquals(ShellAppFunctionGateway.ArrayType.BOOLEAN,
                ShellAppFunctionGateway.arrayType(new JSONArray("[true,false]")));
        assertEquals(ShellAppFunctionGateway.ArrayType.STRING,
                ShellAppFunctionGateway.arrayType(new JSONArray("[\"1\",\"false\"]")));
        assertEquals(ShellAppFunctionGateway.ArrayType.DOCUMENT,
                ShellAppFunctionGateway.arrayType(new JSONArray().put(new JSONObject())
                        .put(new JSONObject().put("schemaType", "Different"))));
    }

    @Test
    public void refusesStringBooleanNullAndNestedArrayCoercion() throws Exception {
        for (final String json : new String[] {
                "[true,\"false\"]", "[1,\"2\"]", "[1.5,\"2\"]",
                "[\"text\",2]", "[{},\"{}\"]", "[null]", "[[]]", "[\"text\",null]"}) {
            final JSONArray values = new JSONArray(json);
            assertThrows(json, IllegalArgumentException.class,
                    () -> ShellAppFunctionGateway.arrayType(values));
        }
    }

    @Test
    public void rejectsOutOfRangeNumbersAndOversizedArrays() throws Exception {
        for (final Object value : new Object[] {
                new BigInteger("9223372036854775808"), new BigDecimal("1E1000")}) {
            final JSONArray values = new JSONArray().put(value);
            assertThrows(IllegalArgumentException.class,
                    () -> ShellAppFunctionGateway.arrayType(values));
        }
        final JSONArray values = new JSONArray();
        for (int index = 0; index < 256; index++) {
            values.put(index);
        }
        assertEquals(ShellAppFunctionGateway.ArrayType.LONG,
                ShellAppFunctionGateway.arrayType(values));
        values.put(256);
        assertThrows(IllegalArgumentException.class,
                () -> ShellAppFunctionGateway.arrayType(values));
    }
}
