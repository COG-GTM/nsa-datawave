package datawave.ingest.parquet.config.helper;

import org.apache.hadoop.conf.Configuration;

import datawave.ingest.data.config.CSVHelper;

/**
 * Data type helper for Parquet input. Parquet files are self-describing, so the CSV header is optional: when no header is configured every column found in
 * the file schema is treated as an event field. Any header that is configured behaves exactly as it does for CSV, i.e., fields outside the header are only
 * kept when {@link #PROCESS_EXTRA_FIELDS} is true.
 */
public class ParquetDataTypeHelper extends CSVHelper {

    public interface Properties {
        /**
         * Field, if any, in each record that carries the Accumulo column visibility for the event
         */
        String COLUMN_VISIBILITY_FIELD = ".data.category.marking.visibility.field";

        /**
         * Delimiter used to join the path elements of nested Parquet groups into a single field name. Defaults to {@value #DEFAULT_FIELD_NAME_DELIMITER}
         */
        String FIELD_NAME_DELIMITER = ".data.parquet.field.name.delimiter";

        /**
         * Whether field names derived from the Parquet schema should be upper cased. Defaults to true, matching the behavior of the CSV ingest helper
         */
        String UPPERCASE_FIELD_NAMES = ".data.parquet.field.name.uppercase";
    }

    public static final String DEFAULT_FIELD_NAME_DELIMITER = "_";

    private String columnVisibilityField = null;
    private String fieldNameDelimiter = DEFAULT_FIELD_NAME_DELIMITER;
    private boolean uppercaseFieldNames = true;

    @Override
    public void setup(Configuration config) throws IllegalArgumentException {
        // make sure the datatype name is resolved before we touch any type-prefixed properties
        super.setup(withParquetDefaults(config));

        String type = getType().typeName();
        this.columnVisibilityField = config.get(type + Properties.COLUMN_VISIBILITY_FIELD);
        this.fieldNameDelimiter = config.get(type + Properties.FIELD_NAME_DELIMITER, DEFAULT_FIELD_NAME_DELIMITER);
        this.uppercaseFieldNames = config.getBoolean(type + Properties.UPPERCASE_FIELD_NAMES, true);
    }

    /**
     * The CSV helper insists on a header and a separator. Neither is meaningful for Parquet, so supply defaults when they are absent: no header, process all
     * fields, and a comma separator for list-valued configuration properties.
     *
     * @param config
     *            the job configuration
     * @return the configuration with Parquet defaults applied
     */
    private Configuration withParquetDefaults(Configuration config) {
        String type = config.get(datawave.ingest.data.config.DataTypeHelper.Properties.DATA_NAME_OVERRIDE,
                        config.get(datawave.ingest.data.config.DataTypeHelper.Properties.DATA_NAME));
        if (type == null) {
            return config;
        }
        if (config.get(type + DATA_HEADER) == null) {
            config.setBoolean(type + DATA_HEADER_ENABLED, false);
            config.setBoolean(type + PROCESS_EXTRA_FIELDS, true);
        }
        if (config.get(type + DATA_SEP) == null) {
            config.set(type + DATA_SEP, ",");
        }
        return config;
    }

    public String getColumnVisibilityField() {
        return columnVisibilityField;
    }

    public String getFieldNameDelimiter() {
        return fieldNameDelimiter;
    }

    public boolean isUppercaseFieldNames() {
        return uppercaseFieldNames;
    }

    /**
     * @return true when the configured header, if any, restricts the fields that are kept
     */
    public boolean isHeaderRestricted() {
        return getHeader() != null && getHeader().length > 0 && !processExtraFields();
    }
}
