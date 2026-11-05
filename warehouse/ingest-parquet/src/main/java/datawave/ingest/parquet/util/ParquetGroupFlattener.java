package datawave.ingest.parquet.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.DateLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.DecimalLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.ListLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.MapLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.StringLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimestampLogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Converts a Parquet {@link Group} (one record) into a flat multimap of field name to string value, which is the representation the rest of the ingest
 * pipeline expects (the same shape the CSV reader produces from a delimited line).
 * <ul>
 * <li>Primitive columns become a single field value. Repeated primitives become multiple values of the same field.</li>
 * <li>Nested groups are flattened by joining the path elements with the configured delimiter (e.g. {@code ADDRESS_CITY}). The structural wrapper groups used by
 * the LIST and MAP logical types ({@code list}/{@code element}, {@code key_value}/{@code key}/{@code value}) do not contribute to the field name.</li>
 * <li>Logical types are rendered in a text form that matches what the same column would hold in a CSV file: ISO-8601 dates/times (UTC), plain decimals,
 * {@code true}/{@code false} booleans.</li>
 * </ul>
 * The flattened record can also be serialized to and from a canonical JSON form, which is what the record reader stores as the event's raw data so that the
 * ingest helper can reconstruct the fields without re-reading the Parquet file.
 */
public class ParquetGroupFlattener {

    /** ISO-8601 UTC instant; fractional seconds are printed only when present, in groups of three digits */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final long JULIAN_DAY_OF_EPOCH = 2440588L;
    private static final long NANOS_PER_DAY = 86400L * 1_000_000_000L;

    private final String delimiter;
    private final boolean uppercase;

    public ParquetGroupFlattener(String delimiter, boolean uppercase) {
        this.delimiter = delimiter == null ? "" : delimiter;
        this.uppercase = uppercase;
    }

    /**
     * Flatten a record into field name / value pairs. Field order follows the schema so the result is deterministic for a given file.
     *
     * @param group
     *            the record
     * @return the flattened fields; never null
     */
    public Multimap<String,String> flatten(Group group) {
        Multimap<String,String> fields = LinkedListMultimap.create();
        flatten(group, "", fields);
        return fields;
    }

    private void flatten(Group group, String prefix, Multimap<String,String> fields) {
        GroupType schema = group.getType();
        for (int i = 0; i < schema.getFieldCount(); i++) {
            Type field = schema.getType(i);
            int count = group.getFieldRepetitionCount(i);
            if (count == 0) {
                continue;
            }
            String name = isStructuralWrapper(schema, field) ? prefix : join(prefix, field.getName());
            for (int r = 0; r < count; r++) {
                if (field.isPrimitive()) {
                    String value = primitiveToString(group, i, r, field.asPrimitiveType());
                    if (value != null) {
                        fields.put(name, value);
                    }
                } else {
                    flatten(group.getGroup(i, r), name, fields);
                }
            }
        }
    }

    /**
     * The LIST and MAP logical types wrap their contents in intermediate groups whose names carry no meaning to a user ({@code list}, {@code element},
     * {@code key_value}). Those names are dropped so a list column {@code TAGS} yields values for {@code TAGS} and not {@code TAGS_LIST_ELEMENT}. Map keys and
     * values retain their {@code key}/{@code value} suffixes so the two sides stay distinguishable.
     */
    private boolean isStructuralWrapper(GroupType parent, Type field) {
        LogicalTypeAnnotation parentAnnotation = parent.getLogicalTypeAnnotation();
        if (parentAnnotation instanceof ListLogicalTypeAnnotation || parentAnnotation instanceof MapLogicalTypeAnnotation) {
            // the repeated "list" / "key_value" group directly under the annotated group
            return !field.isPrimitive() || field.isRepetition(Type.Repetition.REPEATED);
        }
        if (field.isRepetition(Type.Repetition.REPEATED) && !field.isPrimitive()) {
            // legacy 2-level lists: the repeated group's single child is the element
            return false;
        }
        // the "element" child of a LIST's repeated "list" group
        return "element".equals(field.getName()) && parent.isRepetition(Type.Repetition.REPEATED) && parent.getFieldCount() == 1
                        && ("list".equals(parent.getName()) || "array".equals(parent.getName()) || "bag".equals(parent.getName()));
    }

    private String join(String prefix, String name) {
        String n = uppercase ? name.toUpperCase() : name;
        return prefix.isEmpty() ? n : prefix + delimiter + n;
    }

    private String primitiveToString(Group group, int fieldIndex, int index, PrimitiveType type) {
        LogicalTypeAnnotation annotation = type.getLogicalTypeAnnotation();
        switch (type.getPrimitiveTypeName()) {
            case BOOLEAN:
                return Boolean.toString(group.getBoolean(fieldIndex, index));
            case INT32: {
                int v = group.getInteger(fieldIndex, index);
                if (annotation instanceof DateLogicalTypeAnnotation) {
                    return LocalDate.ofEpochDay(v).toString();
                } else if (annotation instanceof DecimalLogicalTypeAnnotation) {
                    return decimal(BigInteger.valueOf(v), (DecimalLogicalTypeAnnotation) annotation);
                } else if (annotation instanceof TimeLogicalTypeAnnotation) {
                    return LocalTime.ofNanoOfDay(v * 1_000_000L).toString();
                }
                return Integer.toString(v);
            }
            case INT64: {
                long v = group.getLong(fieldIndex, index);
                if (annotation instanceof TimestampLogicalTypeAnnotation) {
                    return timestamp(v, ((TimestampLogicalTypeAnnotation) annotation).getUnit());
                } else if (annotation instanceof DecimalLogicalTypeAnnotation) {
                    return decimal(BigInteger.valueOf(v), (DecimalLogicalTypeAnnotation) annotation);
                } else if (annotation instanceof TimeLogicalTypeAnnotation) {
                    TimeUnit unit = ((TimeLogicalTypeAnnotation) annotation).getUnit();
                    return LocalTime.ofNanoOfDay(unit == TimeUnit.MICROS ? v * 1_000L : v).toString();
                }
                return Long.toString(v);
            }
            case INT96:
                return int96Timestamp(group.getInt96(fieldIndex, index));
            case FLOAT:
                return Float.toString(group.getFloat(fieldIndex, index));
            case DOUBLE:
                return Double.toString(group.getDouble(fieldIndex, index));
            case FIXED_LEN_BYTE_ARRAY:
            case BINARY: {
                Binary b = group.getBinary(fieldIndex, index);
                if (annotation instanceof DecimalLogicalTypeAnnotation) {
                    return decimal(new BigInteger(b.getBytes()), (DecimalLogicalTypeAnnotation) annotation);
                } else if (annotation instanceof StringLogicalTypeAnnotation || annotation instanceof LogicalTypeAnnotation.EnumLogicalTypeAnnotation
                                || annotation instanceof LogicalTypeAnnotation.JsonLogicalTypeAnnotation) {
                    return b.toStringUsingUTF8();
                } else if (annotation instanceof LogicalTypeAnnotation.UUIDLogicalTypeAnnotation) {
                    return uuid(b.getBytes());
                }
                // un-annotated binary: assume text, which is what every CSV column would have been
                return b.toStringUsingUTF8();
            }
            default:
                return group.getValueToString(fieldIndex, index);
        }
    }

    private static String decimal(BigInteger unscaled, DecimalLogicalTypeAnnotation annotation) {
        return new BigDecimal(unscaled, annotation.getScale()).toPlainString();
    }

    /**
     * Render an epoch value as an ISO-8601 UTC instant, keeping whatever sub-second precision the column carries (e.g. {@code 2020-01-01T00:00:00.123456Z}).
     */
    static String timestamp(long value, TimeUnit unit) {
        long perSecond;
        switch (unit) {
            case MICROS:
                perSecond = 1_000_000L;
                break;
            case NANOS:
                perSecond = 1_000_000_000L;
                break;
            default:
                perSecond = 1_000L;
        }
        long seconds = Math.floorDiv(value, perSecond);
        long nanos = Math.floorMod(value, perSecond) * (1_000_000_000L / perSecond);
        return TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(seconds, nanos));
    }

    private static String int96Timestamp(Binary binary) {
        ByteBuffer buf = binary.toByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        long nanosOfDay = buf.getLong();
        int julianDay = buf.getInt();
        long epochDay = julianDay - JULIAN_DAY_OF_EPOCH;
        long seconds = epochDay * 86_400L + Math.floorDiv(nanosOfDay, 1_000_000_000L);
        return TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(seconds, Math.floorMod(nanosOfDay, 1_000_000_000L)));
    }

    private static String uuid(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new java.util.UUID(buf.getLong(), buf.getLong()).toString();
    }

    /**
     * Serialize flattened fields to canonical JSON: an object whose keys are sorted field names and whose values are arrays of strings.
     *
     * @param fields
     *            the flattened fields
     * @return the JSON text
     */
    public static String toJson(Multimap<String,String> fields) {
        Map<String,JsonArray> sorted = new TreeMap<>();
        for (Map.Entry<String,String> e : fields.entries()) {
            sorted.computeIfAbsent(e.getKey(), k -> new JsonArray()).add(e.getValue());
        }
        JsonObject obj = new JsonObject();
        for (Map.Entry<String,JsonArray> e : sorted.entrySet()) {
            obj.add(e.getKey(), e.getValue());
        }
        return obj.toString();
    }

    /**
     * Inverse of {@link #toJson(Multimap)}.
     *
     * @param json
     *            the JSON text
     * @return the flattened fields
     */
    public static Multimap<String,String> fromJson(String json) {
        Multimap<String,String> fields = LinkedListMultimap.create();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String,JsonElement> e : obj.entrySet()) {
            if (e.getValue().isJsonArray()) {
                for (JsonElement v : e.getValue().getAsJsonArray()) {
                    fields.put(e.getKey(), v.getAsString());
                }
            } else if (!e.getValue().isJsonNull()) {
                fields.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return fields;
    }
}
