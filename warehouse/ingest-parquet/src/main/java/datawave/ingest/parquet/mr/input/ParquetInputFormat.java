package datawave.ingest.parquet.mr.input;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;

import datawave.ingest.data.RawRecordContainer;

/**
 * Input format for Parquet files. Each file is read in its entirety by a single {@link ParquetRecordReader}; splits are disabled so that a record is never
 * divided between mappers.
 */
public class ParquetInputFormat extends SequenceFileInputFormat<LongWritable,RawRecordContainer> {

    @Override
    protected boolean isSplitable(JobContext context, Path filename) {
        return false;
    }

    @Override
    public RecordReader<LongWritable,RawRecordContainer> createRecordReader(InputSplit split, TaskAttemptContext context) {
        return new RecordReader<>() {
            private final ParquetRecordReader delegate = new ParquetRecordReader();

            @Override
            public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
                delegate.initialize(split, context);
            }

            @Override
            public boolean nextKeyValue() throws IOException {
                return delegate.nextKeyValue();
            }

            @Override
            public LongWritable getCurrentKey() {
                return delegate.getCurrentKey();
            }

            @Override
            public RawRecordContainer getCurrentValue() {
                return delegate.getEvent();
            }

            @Override
            public float getProgress() {
                return delegate.getProgress();
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }
        };
    }
}
