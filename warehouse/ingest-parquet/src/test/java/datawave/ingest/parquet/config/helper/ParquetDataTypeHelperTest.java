package datawave.ingest.parquet.config.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import datawave.ingest.data.TypeRegistry;
import datawave.ingest.data.config.DataTypeHelper;

public class ParquetDataTypeHelperTest {

    private Configuration conf;

    @BeforeEach
    public void setup() {
        conf = new Configuration();
        conf.addResource(ClassLoader.getSystemResource("config/ingest/all-config.xml"));
        conf.addResource(ClassLoader.getSystemResource("config/ingest/parquet-ingest-config.xml"));
        TypeRegistry.reset();
        TypeRegistry.getInstance(conf);
    }

    @Test
    public void testConfiguredHeader() {
        ParquetDataTypeHelper helper = new ParquetDataTypeHelper();
        helper.setup(conf);
        assertEquals("myparquet", helper.getType().typeName());
        assertEquals(12, helper.getHeader().length);
        assertTrue(helper.processExtraFields());
        assertFalse(helper.isHeaderRestricted());
        assertEquals("SECURITY_MARKING", helper.getColumnVisibilityField());
        assertEquals("_", helper.getFieldNameDelimiter());
        assertTrue(helper.isUppercaseFieldNames());
    }

    @Test
    public void testHeaderRestricted() {
        conf.setBoolean("myparquet.data.process.extra.fields", false);
        ParquetDataTypeHelper helper = new ParquetDataTypeHelper();
        helper.setup(conf);
        assertTrue(helper.isHeaderRestricted());
    }

    @Test
    public void testHeaderAndSeparatorAreOptional() {
        Configuration minimal = new Configuration();
        minimal.set(DataTypeHelper.Properties.DATA_NAME, "bare");
        minimal.set("bare.data.parquet.field.name.delimiter", ".");
        minimal.set("bare.data.parquet.field.name.uppercase", "false");
        TypeRegistry.reset();
        TypeRegistry.getInstance(minimal);

        ParquetDataTypeHelper helper = new ParquetDataTypeHelper();
        helper.setup(minimal);
        assertArrayEquals(new String[0], helper.getHeader() == null ? new String[0] : helper.getHeader());
        assertTrue(helper.processExtraFields());
        assertFalse(helper.isHeaderRestricted());
        assertEquals(".", helper.getFieldNameDelimiter());
        assertFalse(helper.isUppercaseFieldNames());
        assertEquals(",", helper.getSeparator());
    }
}
