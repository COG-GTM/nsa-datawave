package datawave.ingest.parquet.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;

/**
 * Test support: writes Parquet files whose columns are all UTF-8 strings, so that a delimited (CSV) fixture can be turned into a Parquet fixture carrying the
 * exact same logical records. A column is written as {@code repeated} when any row carries more than one value for it (values separated by the multi-value
 * separator); otherwise it is {@code optional}.
 */
public class ParquetTestFileWriter {

    public static final String DEFAULT_MULTI_VALUE_SEPARATOR = ";";

    private ParquetTestFileWriter() {}

    /**
     * Convert a header-less CSV file to Parquet using the supplied column names.
     */
    public static void csvToParquet(File csv, File parquet, List<String> columns, String multiValueSeparator) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                rows.add(line.split(",", -1));
            }
        }
        writeStrings(parquet, columns, rows, multiValueSeparator);
    }

    /**
     * Write rows of string values to a Parquet file. Row arrays may be shorter than the column list; missing or empty values are left null.
     */
    public static void writeStrings(File parquet, List<String> columns, List<String[]> rows, String multiValueSeparator) throws IOException {
        Set<String> repeated = new HashSet<>();
        if (multiValueSeparator != null) {
            for (String[] row : rows) {
                for (int i = 0; i < row.length && i < columns.size(); i++) {
                    if (row[i] != null && row[i].contains(multiValueSeparator)) {
                        repeated.add(columns.get(i));
                    }
                }
            }
        }

        Types.MessageTypeBuilder builder = Types.buildMessage();
        for (String column : columns) {
            Type.Repetition rep = repeated.contains(column) ? Type.Repetition.REPEATED : Type.Repetition.OPTIONAL;
            builder.addField(Types.primitive(PrimitiveType.PrimitiveTypeName.BINARY, rep).as(LogicalTypeAnnotation.stringType()).named(column));
        }
        MessageType schema = builder.named("record");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);

        if (parquet.exists() && !parquet.delete()) {
            throw new IOException("could not replace " + parquet);
        }
        try (ParquetWriter<Group> writer = newWriter(parquet, schema)) {
            for (String[] row : rows) {
                Group group = factory.newGroup();
                for (int i = 0; i < row.length && i < columns.size(); i++) {
                    String value = row[i];
                    if (value == null || value.isEmpty()) {
                        continue;
                    }
                    String column = columns.get(i);
                    if (repeated.contains(column)) {
                        for (String v : value.split(java.util.regex.Pattern.quote(multiValueSeparator))) {
                            group.append(column, v);
                        }
                    } else {
                        group.append(column, value);
                    }
                }
                writer.write(group);
            }
        }
    }

    public static ParquetWriter<Group> newWriter(File parquet, MessageType schema) throws IOException {
        return ExampleParquetWriter.builder(new Path(parquet.toURI())).withConf(new Configuration()).withType(schema)
                        .withCompressionCodec(CompressionCodecName.UNCOMPRESSED).build();
    }
}
