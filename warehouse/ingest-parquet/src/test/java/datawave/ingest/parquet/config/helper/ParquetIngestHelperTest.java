package datawave.ingest.parquet.config.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import datawave.ingest.data.RawRecordContainer;
import datawave.ingest.data.RawRecordContainerImpl;
import datawave.ingest.data.TypeRegistry;
import datawave.ingest.data.config.NormalizedContentInterface;
import datawave.ingest.data.config.NormalizedFieldAndValue;
import datawave.ingest.parquet.util.ParquetGroupFlattener;

public class ParquetIngestHelperTest {

    private Configuration conf;

    @BeforeEach
    public void setup() {
        conf = new Configuration();
        conf.addResource(ClassLoader.getSystemResource("config/ingest/all-config.xml"));
        conf.addResource(ClassLoader.getSystemResource("config/ingest/parquet-ingest-config.xml"));
        TypeRegistry.reset();
        TypeRegistry.getInstance(conf);
    }

    private RawRecordContainer event(Multimap<String,String> fields) {
        RawRecordContainer event = new RawRecordContainerImpl();
        event.setDataType(TypeRegistry.getType("myparquet"));
        event.setTimestamp(new Date().getTime());
        event.setRawData(ParquetGroupFlattener.toJson(fields).getBytes());
        event.generateId(null);
        return event;
    }

    private static Multimap<String,String> record() {
        Multimap<String,String> fields = LinkedListMultimap.create();
        fields.put("EVENT_ID", "trrn.n2016117aamy.0000000179");
        fields.put("LANGUAGE", "NONE");
        fields.put("ORIGINAL_SIZE", "3173");
        fields.put("EVENT_DATE", "2016-04-26T01:31:53Z");
        fields.put("PARAGRAPH_CHECKSUM", "aaa");
        fields.put("PARAGRAPH_CHECKSUM", "bbb");
        fields.put("EDGE_VERTEX_FROM", "VERTEX1");
        fields.put("SUMMARY.summary_0", "grouped value");
        return fields;
    }

    @Test
    public void testSetup() {
        ParquetIngestHelper helper = new ParquetIngestHelper();
        helper.setup(conf);
        assertNotNull(helper.getEmbeddedHelper());
        assertTrue(helper.getEmbeddedHelper() instanceof ParquetDataTypeHelper);
    }

    @Test
    public void testGetEventFields() {
        ParquetIngestHelper helper = new ParquetIngestHelper();
        helper.setup(conf);

        Multimap<String,NormalizedContentInterface> fieldMap = helper.getEventFields(event(record()));
        assertEquals(7, fieldMap.keySet().size());
        assertEquals(8, fieldMap.values().size());
        assertEquals(2, fieldMap.get("PARAGRAPH_CHECKSUM").size());
        // default LcNoDiacriticsType normalization
        assertEquals("vertex1", fieldMap.get("EDGE_VERTEX_FROM").iterator().next().getIndexedFieldValue());
        assertEquals("VERTEX1", fieldMap.get("EDGE_VERTEX_FROM").iterator().next().getEventFieldValue());
        // *SIZE is configured as a NumberType
        assertEquals("+dE3.173", fieldMap.get("ORIGINAL_SIZE").iterator().next().getIndexedFieldValue());
        // grouping notation is honored, as it is for CSV and JSON
        assertTrue(fieldMap.containsKey("SUMMARY"));
        assertTrue(((NormalizedFieldAndValue) fieldMap.get("SUMMARY").iterator().next()).isGrouped());
        assertFalse(((NormalizedFieldAndValue) fieldMap.get("LANGUAGE").iterator().next()).isGrouped());
    }

    @Test
    public void testAllowAndDisallowLists() {
        conf.set("myparquet.data.field.disallowlist", "LANGUAGE");
        TypeRegistry.reset();
        TypeRegistry.getInstance(conf);
        ParquetIngestHelper helper = new ParquetIngestHelper();
        helper.setup(conf);
        Multimap<String,NormalizedContentInterface> fieldMap = helper.getEventFields(event(record()));
        assertFalse(fieldMap.containsKey("LANGUAGE"));
        assertTrue(fieldMap.containsKey("EVENT_ID"));

        conf.unset("myparquet.data.field.disallowlist");
        conf.set("myparquet.data.field.allowlist", "EVENT_ID,LANGUAGE");
        TypeRegistry.reset();
        TypeRegistry.getInstance(conf);
        helper = new ParquetIngestHelper();
        helper.setup(conf);
        fieldMap = helper.getEventFields(event(record()));
        assertEquals(2, fieldMap.keySet().size());
        assertTrue(fieldMap.containsKey("EVENT_ID"));
        assertTrue(fieldMap.containsKey("LANGUAGE"));
    }

    @Test
    public void testConfiguredMultiValuedScalarIsSplitLikeCsv() {
        conf.set("myparquet.data.fields.multivalued", "STATE:REGION,TAGS");
        conf.set("myparquet.data.multivalued.separator", ";");
        TypeRegistry.reset();
        TypeRegistry.getInstance(conf);
        ParquetIngestHelper helper = new ParquetIngestHelper();
        helper.setup(conf);

        Multimap<String,String> fields = record();
        fields.put("STATE", "Texas ; Oklahoma");
        fields.put("TAGS", "one\\;two;three");
        fields.put("CITY", "Paris;Dallas");
        Multimap<String,NormalizedContentInterface> fieldMap = helper.getEventFields(event(fields));
        // SOURCE:DESTINATION alias, per-value trimming
        assertFalse(fieldMap.containsKey("STATE"));
        assertEquals(2, fieldMap.get("REGION").size());
        java.util.Set<String> regions = new java.util.TreeSet<>();
        for (NormalizedContentInterface n : fieldMap.get("REGION")) {
            regions.add(n.getEventFieldValue());
        }
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("Oklahoma", "Texas")), regions);
        // escaped separators are not split and the escape is removed
        java.util.Set<String> tags = new java.util.TreeSet<>();
        for (NormalizedContentInterface n : fieldMap.get("TAGS")) {
            tags.add(n.getEventFieldValue());
        }
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("one;two", "three")), tags);
        // a multi-valued field without the separator is kept whole (under its alias)
        fields = record();
        fields.put("STATE", "Texas");
        fieldMap = helper.getEventFields(event(fields));
        assertEquals(1, fieldMap.get("REGION").size());
        assertEquals("Texas", fieldMap.get("REGION").iterator().next().getEventFieldValue());
        fields.put("CITY", "Paris;Dallas");
        fieldMap = helper.getEventFields(event(fields));
        // CITY is not configured as multi-valued, so it is left intact
        assertEquals(1, fieldMap.get("CITY").size());
        assertEquals("Paris;Dallas", fieldMap.get("CITY").iterator().next().getEventFieldValue());
    }

    @Test
    public void testRoundTripThroughJson() {
        Multimap<String,String> fields = record();
        Multimap<String,String> copy = ParquetGroupFlattener.fromJson(ParquetGroupFlattener.toJson(fields));
        assertEquals(fields.keySet(), copy.keySet());
        assertEquals(fields.get("PARAGRAPH_CHECKSUM"), copy.get("PARAGRAPH_CHECKSUM"));
    }
}
