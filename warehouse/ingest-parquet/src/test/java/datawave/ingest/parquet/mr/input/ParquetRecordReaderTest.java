package datawave.ingest.parquet.mr.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;

import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.junit.jupiter.api.Test;

import datawave.ingest.data.RawRecordContainer;
import datawave.ingest.data.TypeRegistry;

public class ParquetRecordReaderTest {

    protected ParquetRecordReader init(boolean parseHeaderOnly) throws Exception {
        Configuration conf = new Configuration();
        conf.addResource(ClassLoader.getSystemResource("config/ingest/all-config.xml"));
        conf.addResource(ClassLoader.getSystemResource("config/ingest/parquet-ingest-config.xml"));
        conf.set("myparquet.data.process.extra.fields", String.valueOf(!parseHeaderOnly));

        URL data = ParquetRecordReaderTest.class.getResource("/input/my.parquet");
        assertNotNull(data);

        TypeRegistry.reset();
        TypeRegistry.getInstance(conf);

        File dataFile = new File(data.toURI());
        Path p = new Path(dataFile.toURI().toString());
        InputSplit split = new FileSplit(p, 0, dataFile.length(), null);
        TaskAttemptContext ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID());

        ParquetRecordReader reader = new ParquetRecordReader();
        reader.initialize(split, ctx);
        return reader;
    }

    @Test
    public void testInitialize() throws Exception {
        ParquetRecordReader reader = init(true);
        reader.close();
    }

    @Test
    public void testOneRecordAllFields() throws Exception {
        ParquetRecordReader reader = init(false);
        reader.setInputDate(System.currentTimeMillis());
        assertTrue(reader.nextKeyValue());
        RawRecordContainer event = reader.getEvent();
        assertNotNull(event);
        // 11 header columns + UUID, PARAGRAPH_CHECKSUM, EDGE_VERTEX_FROM, EDGE_VERTEX_TO, FLAG, SCORE, MY_DATE, AMOUNT
        assertEquals(19, reader.getCurrentFields().keySet().size());
        assertEquals(21, reader.getCurrentFields().values().size());
        assertEquals(3, reader.getCurrentFields().get("PARAGRAPH_CHECKSUM").size());
        assertEquals("VERTEX3", reader.getCurrentFields().get("EDGE_VERTEX_TO").iterator().next());
        assertEquals("3173", reader.getCurrentFields().get("ORIGINAL_SIZE").iterator().next());
        assertEquals("2016-04-26T01:31:53Z", reader.getCurrentFields().get("EVENT_DATE").iterator().next());
        assertEquals("2015-01-01", reader.getCurrentFields().get("MY_DATE").iterator().next());
        assertEquals("1234.50", reader.getCurrentFields().get("AMOUNT").iterator().next());
        assertEquals("true", reader.getCurrentFields().get("FLAG").iterator().next());
        reader.close();
    }

    @Test
    public void testOneRecordHeaderFieldsOnly() throws Exception {
        ParquetRecordReader reader = init(true);
        reader.setInputDate(System.currentTimeMillis());
        assertTrue(reader.nextKeyValue());
        assertNotNull(reader.getEvent());
        assertEquals(11, reader.getCurrentFields().keySet().size());
        assertFalse(reader.getCurrentFields().containsKey("UUID"));
        reader.close();
    }

    @Test
    public void testEventDecoration() throws Exception {
        ParquetRecordReader reader = init(false);
        reader.setInputDate(0L);
        assertTrue(reader.nextKeyValue());
        RawRecordContainer event = reader.getEvent();
        // EVENT_DATE drives the timestamp, SECURITY_MARKING drives the visibility, EVENT_ID drives the UID
        assertEquals(1461634313000L, event.getTimestamp());
        assertEquals(new ColumnVisibility("PUBLIC"), event.getVisibility());
        assertEquals("myparquet", event.getDataType().typeName());
        assertNotNull(event.getId());
        assertTrue(event.getErrors().isEmpty());
        // raw data is the canonical JSON of the flattened fields
        String raw = new String(event.getRawData());
        assertTrue(raw.startsWith("{\"AMOUNT\":[\"1234.50\"]"), raw);
        reader.close();
    }

    @Test
    public void testGetAllRecords() throws Exception {
        ParquetRecordReader reader = init(false);
        reader.setInputDate(System.currentTimeMillis());

        assertTrue(reader.nextKeyValue());
        assertNotNull(reader.getEvent());
        assertEquals(19, reader.getCurrentFields().keySet().size());
        assertEquals(21, reader.getCurrentFields().values().size());

        assertTrue(reader.nextKeyValue());
        assertNotNull(reader.getEvent());
        assertEquals(16, reader.getCurrentFields().keySet().size());
        assertEquals(16, reader.getCurrentFields().values().size());
        assertEquals("MIME", reader.getCurrentFields().get("PARENT_FILETYPE").iterator().next());

        assertTrue(reader.nextKeyValue());
        assertNotNull(reader.getEvent());
        assertEquals(11, reader.getCurrentFields().keySet().size());
        assertEquals(11, reader.getCurrentFields().values().size());

        assertFalse(reader.nextKeyValue());
        assertEquals(1.0f, reader.getProgress());
        reader.close();
    }
}
