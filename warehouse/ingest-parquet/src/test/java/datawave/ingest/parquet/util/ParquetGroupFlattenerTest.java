package datawave.ingest.parquet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Multimap;

public class ParquetGroupFlattenerTest {

    private static List<Group> read(File file) throws IOException {
        List<Group> groups = new java.util.ArrayList<>();
        try (ParquetReader<Group> reader = ParquetReader.builder(new GroupReadSupport(), new Path(file.toURI())).withConf(new Configuration()).build()) {
            Group g;
            while ((g = reader.read()) != null) {
                groups.add(g);
            }
        }
        return groups;
    }

    @Test
    public void testTypedNestedAndRepeated() throws IOException {
        File file = Files.createTempFile("flattener", ".parquet").toFile();
        file.deleteOnExit();
        ParquetTestFixtures.writeMyParquet(file);
        List<Group> groups = read(file);
        assertEquals(3, groups.size());

        Multimap<String,String> fields = new ParquetGroupFlattener("_", true).flatten(groups.get(0));
        assertEquals(Arrays.asList("718ef8731de49eceaf24ce149710d6f3f854d5f2", "66f00045d715f5afa19ffc1c285140cd01eac190",
                        "d6bdac51c34acde7d55611246280648de11d7a6b"), fields.get("PARAGRAPH_CHECKSUM"));
        assertFalse(fields.containsKey("PARAGRAPH_CHECKSUM_LIST_ELEMENT"));
        assertEquals(Collections.singletonList("VERTEX1"), fields.get("EDGE_VERTEX_FROM"));
        assertEquals(Collections.singletonList("698"), fields.get("PROCESSED_SIZE"));
        assertEquals(Collections.singletonList("0.75"), fields.get("SCORE"));
        assertEquals(Collections.singletonList("2016-04-26T01:31:53Z"), fields.get("EVENT_DATE"));
        assertEquals(Collections.singletonList("2015-01-01"), fields.get("MY_DATE"));
        assertEquals(Collections.singletonList("1234.50"), fields.get("AMOUNT"));
        assertEquals(Collections.singletonList("true"), fields.get("FLAG"));
        assertFalse(fields.containsKey("PARENT_FILETYPE"));

        // alternate delimiter / case handling
        Multimap<String,String> lower = new ParquetGroupFlattener(".", false).flatten(groups.get(0));
        assertTrue(lower.containsKey("EDGE.VERTEX_TO"));
    }

    @Test
    public void testStringColumnsMatchCsv() throws IOException {
        File csv = Files.createTempFile("rows", ".csv").toFile();
        File parquet = Files.createTempFile("rows", ".parquet").toFile();
        csv.deleteOnExit();
        parquet.deleteOnExit();
        Files.write(csv.toPath(), Arrays.asList("20150707,paris-fra-lle-7,Paris,lle-de-France;Centre,France", "20150708,paris-usa-tx-7,Paris,Texas,"));
        List<String> header = Arrays.asList("START_DATE", "EVENT_ID", "CITY", "STATE", "COUNTRY");
        ParquetTestFileWriter.csvToParquet(csv, parquet, header, ";");

        List<Group> groups = read(parquet);
        assertEquals(2, groups.size());
        ParquetGroupFlattener flattener = new ParquetGroupFlattener("_", true);
        Multimap<String,String> first = flattener.flatten(groups.get(0));
        assertEquals(Arrays.asList("lle-de-France", "Centre"), first.get("STATE"));
        assertEquals(Collections.singletonList("France"), first.get("COUNTRY"));
        Multimap<String,String> second = flattener.flatten(groups.get(1));
        assertFalse(second.containsKey("COUNTRY"));
        assertEquals(4, second.keySet().size());
    }

    @Test
    public void testTimestampPrecisionIsPreserved() {
        assertEquals("2020-01-01T00:00:00Z", ParquetGroupFlattener.timestamp(1577836800000L, TimeUnit.MILLIS));
        assertEquals("2020-01-01T00:00:00.123Z", ParquetGroupFlattener.timestamp(1577836800123L, TimeUnit.MILLIS));
        assertEquals("2020-01-01T00:00:00.123456Z", ParquetGroupFlattener.timestamp(1577836800123456L, TimeUnit.MICROS));
        assertEquals("2020-01-01T00:00:00.987654321Z", ParquetGroupFlattener.timestamp(1577836800987654321L, TimeUnit.NANOS));
        assertEquals("1969-12-31T23:59:59.999999Z", ParquetGroupFlattener.timestamp(-1L, TimeUnit.MICROS));
    }

    @Test
    public void testJsonRoundTripIsCanonical() {
        Multimap<String,String> fields = com.google.common.collect.LinkedListMultimap.create();
        fields.put("B", "2");
        fields.put("A", "1");
        fields.put("A", "x\"y");
        String json = ParquetGroupFlattener.toJson(fields);
        assertEquals("{\"A\":[\"1\",\"x\\\"y\"],\"B\":[\"2\"]}", json);
        assertEquals(fields.get("A"), ParquetGroupFlattener.fromJson(json).get("A"));
    }
}
