package datawave.ingest.parquet.util;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

/**
 * Builds the {@code input/my.parquet} test fixture: the header columns of {@code ingest-csv}'s {@code my.csv} plus a sampling of typed, nested, and repeated
 * columns that exercise the logical type conversions. Run {@link #main(String[])} to regenerate the fixture.
 */
public class ParquetTestFixtures {

    public static final MessageType MY_SCHEMA = Types.buildMessage()
                    // header fields shared with my.csv
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("PROCESSING_DATE")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("EVENT_ID")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("LANGUAGE")
                    .required(PrimitiveTypeName.INT32).named("ORIGINAL_SIZE")
                    .required(PrimitiveTypeName.INT64).named("PROCESSED_SIZE")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("FILE_TYPE")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("MD5")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("SHA1")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("SHA256")
                    .required(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.timestampType(true, TimeUnit.MILLIS)).named("EVENT_DATE")
                    .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("SECURITY_MARKING")
                    // extra, optional columns
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("UUID")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("PARENT_FILETYPE")
                    .optionalGroup().as(LogicalTypeAnnotation.listType()).repeatedGroup()
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("element").named("list").named("PARAGRAPH_CHECKSUM")
                    .optionalGroup().optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("VERTEX_FROM")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("VERTEX_TO").named("EDGE")
                    .optional(PrimitiveTypeName.BOOLEAN).named("FLAG").optional(PrimitiveTypeName.DOUBLE).named("SCORE")
                    .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.dateType()).named("MY_DATE")
                    .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.decimalType(2, 18)).named("AMOUNT").named("record");

    public static void writeMyParquet(File out) throws IOException {
        SimpleGroupFactory f = new SimpleGroupFactory(MY_SCHEMA);
        if (out.exists() && !out.delete()) {
            throw new IOException("could not replace " + out);
        }
        try (ParquetWriter<Group> writer = ParquetTestFileWriter.newWriter(out, MY_SCHEMA)) {
            Group r1 = header(f, "2016-04-26 03:00:00", "trrn.n2016117aamy.0000000179", "NONE", 3173, 698L, "MIME", "715555845289dd6ba0f4cbb8a02e5052",
                            "b7472ce04163d18089f05b6a0dc5dffe65f2c9a6", "873e26c91c968525c86690772bdb044d398a51847846944ac8c5109fe732d690",
                            "2016-04-26T01:31:53Z", "PUBLIC");
            r1.append("UUID", "0016dd72-0000-827d-dd4d-001b2163ba09");
            Group list = r1.addGroup("PARAGRAPH_CHECKSUM");
            list.addGroup("list").append("element", "718ef8731de49eceaf24ce149710d6f3f854d5f2");
            list.addGroup("list").append("element", "66f00045d715f5afa19ffc1c285140cd01eac190");
            list.addGroup("list").append("element", "d6bdac51c34acde7d55611246280648de11d7a6b");
            Group edge = r1.addGroup("EDGE");
            edge.append("VERTEX_FROM", "VERTEX1");
            edge.append("VERTEX_TO", "VERTEX3");
            r1.append("FLAG", true);
            r1.append("SCORE", 0.75d);
            r1.append("MY_DATE", (int) LocalDate.of(2015, 1, 1).toEpochDay());
            r1.append("AMOUNT", new BigDecimal("1234.50").unscaledValue().longValue());
            writer.write(r1);

            Group r2 = header(f, "2016-04-26 03:00:00", "trrn.n2016117aamy.0000000179-att-1", "ENGLISH", 2188, 2190L, "MIME-BODY",
                            "6c257114aaca09de9e9c3754baf1015e", "f7fbd49088f28aeb92bf76157ed5f1056fe2f9c0",
                            "7e604bd4cd397bec7a861405f8860cb45892d054a96be66e94ea9b62c118f8a2", "2016-04-26T00:31:53Z", "PUBLIC");
            r2.append("PARENT_FILETYPE", "MIME");
            r2.addGroup("PARAGRAPH_CHECKSUM").addGroup("list").append("element", "718ef8731de49eceaf24ce149710d6f3f854d5f2");
            Group edge2 = r2.addGroup("EDGE");
            edge2.append("VERTEX_FROM", "VERTEX1");
            edge2.append("VERTEX_TO", "VERTEX2");
            r2.append("FLAG", false);
            writer.write(r2);

            Group r3 = header(f, "2016-04-26 03:00:00", "trrn.n2016117aamy.0000000179-att-2", "ENGLISH", 2189, 2191L, "MIME-BODY",
                            "6c257114aaca09de9e9c3754baf1015f", "f7fbd49088f28aeb92bf76157ed5f1056fe2f9c1",
                            "7e604bd4cd397bec7a861405f8860cb45892d054a96be66e94ea9b62c118f8a3", "2016-04-26T02:31:53Z", "PUBLIC");
            writer.write(r3);
        }
    }

    private static Group header(SimpleGroupFactory f, String processingDate, String eventId, String language, int originalSize, long processedSize,
                    String fileType, String md5, String sha1, String sha256, String eventDate, String marking) {
        Group g = f.newGroup();
        g.append("PROCESSING_DATE", processingDate);
        g.append("EVENT_ID", eventId);
        g.append("LANGUAGE", language);
        g.append("ORIGINAL_SIZE", originalSize);
        g.append("PROCESSED_SIZE", processedSize);
        g.append("FILE_TYPE", fileType);
        g.append("MD5", md5);
        g.append("SHA1", sha1);
        g.append("SHA256", sha256);
        g.append("EVENT_DATE", Instant.parse(eventDate).toEpochMilli());
        g.append("SECURITY_MARKING", marking);
        return g;
    }

    /**
     * Usage: {@code ParquetTestFixtures <my.parquet path> [<cities csv> <cities parquet> <comma separated header>]}
     */
    public static void main(String[] args) throws IOException {
        writeMyParquet(new File(args[0]));
        if (args.length >= 4) {
            ParquetTestFileWriter.csvToParquet(new File(args[1]), new File(args[2]), java.util.Arrays.asList(args[3].split(",")),
                            ParquetTestFileWriter.DEFAULT_MULTI_VALUE_SEPARATOR);
        }
    }
}
