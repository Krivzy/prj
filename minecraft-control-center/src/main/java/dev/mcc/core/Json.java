package dev.mcc.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
        .maxStringLength(32768).maxNumberLength(100).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode response", e); }
    }
    public static Map<String,Object> object(String value) {
        try { Map<String,Object> result=MAPPER.readValue(value, new TypeReference<>() {});if(result==null)throw new IllegalArgumentException();return result; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON object"); }
    }
    public static String text(Map<String,Object> data, String key, String fallback) {
        Object value = data.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String s) || s.length() > 32768) throw new IllegalArgumentException("Invalid " + key);
        return s;
    }
}
