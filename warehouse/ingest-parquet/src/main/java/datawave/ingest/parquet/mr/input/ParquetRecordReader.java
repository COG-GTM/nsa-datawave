package datawave.ingest.parquet.mr.input;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import datawave.data.hash.UID;
import datawave.ingest.data.RawRecordContainer;
import datawave.ingest.data.config.DataTypeHelper;
import datawave.ingest.input.reader.AbstractEventRecordReader;
import datawave.ingest.parquet.config.helper.ParquetDataTypeHelper;
import datawave.ingest.parquet.util.ParquetGroupFlattener;

/**
 * Reads one Parquet file, producing one {@link RawRecordContainer} per row. Each row is flattened into field/value pairs with
 * {@link ParquetGroupFlattener}; the canonical JSON form of those pairs becomes the event's raw data so that {@code ParquetIngestHelper} can rebuild the
 * fields downstream.
 */
public class ParquetRecordReader extends AbstractEventRecordReader<BytesWritable> {

    protected ParquetDataTypeHelper parquetHelper;
    protected ParquetGroupFlattener flattener;
    protected ParquetReader<Group> reader;

    protected final LongWritable currentKey = new LongWritable();
    protected final BytesWritable currentValue = new BytesWritable();
    protected Multimap<String,String> currentFields = LinkedListMultimap.create();
    protected Set<String> headerFields = null;

    protected long rowCount = 0;
    protected boolean finished = false;

    @Override
    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
        super.initialize(split, context);

        if (!(split instanceof FileSplit)) {
            throw new IOException("Cannot handle split type " + split.getClass().getName());
        }
        Path path = ((FileSplit) split).getPath();
        Configuration conf = context.getConfiguration();
        reader = ParquetReader.builder(new GroupReadSupport(), path).withConf(conf).build();
    }

    @Override
    public void initializeEvent(Configuration conf) throws IOException {
        super.initializeEvent(conf);
        parquetHelper = (ParquetDataTypeHelper) helper;
        flattener = new ParquetGroupFlattener(parquetHelper.getFieldNameDelimiter(), parquetHelper.isUppercaseFieldNames());
        if (parquetHelper.isHeaderRestricted()) {
            headerFields = new HashSet<>(Arrays.asList(parquetHelper.getHeader()));
        }
    }

    @Override
    protected DataTypeHelper createHelper(Configuration conf) {
        ParquetDataTypeHelper helper = new ParquetDataTypeHelper();
        helper.setup(conf);
        return helper;
    }

    @Override
    public boolean nextKeyValue() throws IOException {
        if (finished || reader == null) {
            return false;
        }
        Group group = reader.read();
        if (group == null) {
            finished = true;
            return false;
        }

        event.clear();
        // configured UID columns may be absent from a row; never let a previous row's value leak into this one
        uidOverrideFields.replaceAll((field, value) -> null);
        currentKey.set(rowCount++);
        currentFields = flattener.flatten(group);
        if (headerFields != null) {
            currentFields.keySet().retainAll(headerFields);
        }
        byte[] raw = ParquetGroupFlattener.toJson(currentFields).getBytes(StandardCharsets.UTF_8);
        currentValue.set(raw, 0, raw.length);
        return true;
    }

    @Override
    public RawRecordContainer getEvent() {
        super.getEvent();

        if (StringUtils.isEmpty(eventDateFieldName)) {
            event.setTimestamp(this.inputDate);
        }

        for (Map.Entry<String,String> entry : currentFields.entries()) {
            if (entry.getValue() != null) {
                checkField(entry.getKey(), entry.getValue());
            }
        }

        decorateEvent();

        event.setRawData(currentValue.copyBytes());

        if (!event.isTimestampSet()) {
            event.setTimestamp(System.currentTimeMillis());
        }

        UID newUID = uidOverrideFields.containsValue(null) ? null : uidOverride(event);
        if (null != newUID) {
            event.setId(newUID);
        } else {
            event.generateId(null);
        }

        checkSecurityMarkings();
        enforcePolicy(event);

        return event;
    }

    /**
     * Hook for subclasses to adjust the event after the fields have been examined but before the UID is assigned and policy is enforced.
     */
    protected void decorateEvent() {
        // no-op by default
    }

    protected void checkSecurityMarkings() {
        if (null == event.getSecurityMarkings() || event.getSecurityMarkings().isEmpty()) {
            event.setSecurityMarkings(this.markingsHelper.getDefaultMarkings());
        }
    }

    @Override
    protected void checkField(final String fieldName, final String fieldValue) {
        super.checkField(fieldName, fieldValue);
        if (fieldName.equals(parquetHelper.getColumnVisibilityField())) {
            event.setVisibility(new ColumnVisibility(fieldValue));
        }
    }

    @Override
    public LongWritable getCurrentKey() {
        return currentKey;
    }

    @Override
    public BytesWritable getCurrentValue() {
        return currentValue;
    }

    /**
     * @return the flattened fields of the most recently read row
     */
    public Multimap<String,String> getCurrentFields() {
        return currentFields;
    }

    @Override
    public float getProgress() {
        return finished ? 1.0f : 0.0f;
    }

    @Override
    public synchronized void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
