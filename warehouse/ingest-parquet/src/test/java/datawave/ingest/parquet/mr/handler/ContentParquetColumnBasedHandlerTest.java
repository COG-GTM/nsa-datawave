package datawave.ingest.parquet.mr.handler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.TimeZone;

import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import datawave.ingest.data.RawRecordContainer;
import datawave.ingest.data.TypeRegistry;
import datawave.ingest.mapreduce.handler.edge.ProtobufEdgeDataTypeHandler;
import datawave.ingest.mapreduce.handler.shard.ShardedDataTypeHandler;
import datawave.ingest.mapreduce.job.BulkIngestKey;
import datawave.ingest.parquet.config.helper.ParquetDataTypeHelper;
import datawave.ingest.parquet.config.helper.ParquetIngestHelper;
import datawave.ingest.parquet.mr.input.ParquetRecordReader;
import datawave.util.TableName;

public class ContentParquetColumnBasedHandlerTest {

    private static final boolean PRINT_GENERATED_KEYS_ONLY_ON_FAIL = true;
    private Configuration conf;
    private static final Path edgeKeyVersionCachePath = Paths.get(System.getProperty("user.dir"), "edge-key-version.txt");
    private static final Logger log = Logger.getLogger(ContentParquetColumnBasedHandlerTest.class);
    private static final Enumeration<?> rootAppenders = Logger.getRootLogger().getAllAppenders();

    @BeforeAll
    public static void setupSystemSettings() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        System.setProperty("file.encoding", "UTF8");
        try {
            Files.write(edgeKeyVersionCachePath, "1\t1970-01-01T00:00:00.000Z".getBytes());
        } catch (IOException io) {
            log.fatal("Could not create " + edgeKeyVersionCachePath);
        }
    }

    @AfterAll
    public static void tearDown() {
        Logger.getRootLogger().removeAllAppenders();
        while (rootAppenders.hasMoreElements()) {
            Appender appender = (Appender) rootAppenders.nextElement();
            Logger.getRootLogger().addAppender(appender);
        }
        try {
            Files.deleteIfExists(edgeKeyVersionCachePath);
        } catch (IOException io) {
            log.error("Could not delete " + edgeKeyVersionCachePath);
        }
    }

    private ParquetRecordReader getParquetRecordReader(String file) throws IOException, URISyntaxException {
        InputSplit split = ColumnBasedHandlerTestUtilJUnit5.getSplit(file);
        TaskAttemptContext ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID());
        TypeRegistry.reset();
        TypeRegistry.getInstance(ctx.getConfiguration());
        log.debug(TypeRegistry.getContents());
        ParquetRecordReader reader = new ParquetRecordReader();
        reader.initialize(split, ctx);
        return reader;
    }

    @BeforeEach
    public void setup() {
        TypeRegistry.reset();
        conf = new Configuration();
        conf.setInt(ShardedDataTypeHandler.NUM_SHARDS, 1);
        conf.set(ShardedDataTypeHandler.SHARD_TNAME, TableName.SHARD);
        conf.set(ShardedDataTypeHandler.SHARD_GIDX_TNAME, TableName.SHARD_INDEX);
        conf.set(ShardedDataTypeHandler.SHARD_GRIDX_TNAME, TableName.SHARD_RINDEX);
        conf.set(ShardedDataTypeHandler.SHARD_DAY_INDEX_TABLE_NAME, TableName.SHARD_DAY_INDEX);
        conf.set(ShardedDataTypeHandler.SHARD_YEAR_INDEX_TABLE_NAME, TableName.SHARD_YEAR_INDEX);
    }

    @Test
    public void testParquetContentHandlers() throws Exception {
        conf.addResource(ClassLoader.getSystemResource("config/ingest/all-config.xml"));
        conf.addResource(ClassLoader.getSystemResource("config/ingest/parquet-ingest-config.xml"));
        conf.addResource(ClassLoader.getSystemResource("config/ingest/edge-ingest-config.xml"));
        conf.addResource(ClassLoader.getSystemResource("config/ingest/metadata-config.xml"));
        TypeRegistry.getInstance(conf);

        ParquetDataTypeHelper helper = new ParquetDataTypeHelper();
        helper.setup(conf);

        ParquetIngestHelper ingestHelper = new ParquetIngestHelper();
        ingestHelper.setup(conf);

        TaskAttemptContext context = new TaskAttemptContextImpl(conf, new TaskAttemptID());
        ContentParquetColumnBasedHandler<Text> parquetHandler = new ContentParquetColumnBasedHandler<>();
        parquetHandler.setup(context);

        ParquetRecordReader reader = getParquetRecordReader("/input/my.parquet");
        assertTrue(reader.nextKeyValue(), "First Record did not read properly?");
        RawRecordContainer event = reader.getEvent();
        assertNotNull(event, "Event 1 was null.");
        assertTrue(event.getErrors().isEmpty(), "Event 1 has parsing errors");

        ProtobufEdgeDataTypeHandler<Text,BulkIngestKey,Value> edgeHandler = new ProtobufEdgeDataTypeHandler<>();
        edgeHandler.setup(context);

        ColumnBasedHandlerTestUtilJUnit5.processEvent(parquetHandler, edgeHandler, event, 0, 0, 0, 0, PRINT_GENERATED_KEYS_ONLY_ON_FAIL);
        reader.close();
    }
}
