package datawave.query.parquet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import datawave.core.query.configuration.GenericQueryConfiguration;
import datawave.ingest.parquet.util.ParquetTestFileWriter;
import datawave.microservice.query.QueryImpl;
import datawave.query.QueryTestTableHelper;
import datawave.query.attributes.Attribute;
import datawave.query.attributes.Attributes;
import datawave.query.attributes.Document;
import datawave.query.function.deserializer.KryoDocumentDeserializer;
import datawave.query.iterator.profile.FinalDocumentTrackingIterator;
import datawave.query.testframework.AbstractFunctionalQueryJUnit5;
import datawave.query.testframework.AccumuloSetupJUnit5;
import datawave.query.testframework.CitiesDataType;
import datawave.query.testframework.CitiesDataType.CityField;
import datawave.query.testframework.CityDataManager;
import datawave.query.testframework.DataTypeHadoopConfig;
import datawave.query.testframework.FieldConfig;
import datawave.query.testframework.FileType;
import datawave.query.testframework.GenericCityFields;
import datawave.query.testframework.ParquetCitiesDataType;

/**
 * Ingests the same records twice, once from a CSV fixture through the CSV type and once from a Parquet fixture through the Parquet type, into two in-memory
 * Accumulo instances and asserts that the query API returns identical documents for each.
 */
public class ParquetCsvEquivalenceQueryTest extends AbstractFunctionalQueryJUnit5 {

    @RegisterExtension
    public static AccumuloSetupJUnit5 csvSetup = new AccumuloSetupJUnit5();
    @RegisterExtension
    public static AccumuloSetupJUnit5 parquetSetup = new AccumuloSetupJUnit5();

    private static final Logger log = Logger.getLogger(ParquetCsvEquivalenceQueryTest.class);

    private static final String DATATYPE = "paris";
    private static final String CSV_FIXTURE = "input/paris-cities.csv";
    private static final String PARQUET_FIXTURE = "input/paris-cities.parquet";
    /** UID derived from the event id so the record ids match across the two ingests */
    private static final String UID_FIELDS = ".data.category.uid.fields";

    private static AccumuloClient csvClient;
    private static AccumuloClient parquetClient;

    @BeforeAll
    public static void setup() throws Exception {
        // the Parquet fixture is a committed twin of the CSV fixture; rebuild it so the two can never drift apart
        File csv = new File(ParquetCsvEquivalenceQueryTest.class.getClassLoader().getResource(CSV_FIXTURE).toURI());
        File parquet = new File(csv.getParentFile(), "paris-cities.parquet");
        ParquetTestFileWriter.csvToParquet(csv, parquet, CityField.headers(), ParquetTestFileWriter.DEFAULT_MULTI_VALUE_SEPARATOR);

        FieldConfig fields = new GenericCityFields();
        fields.addIndexField(CityField.CODE.name());
        for (String idx : fields.getIndexFields()) {
            fields.addReverseIndexField(idx);
        }

        CityDataManager.newInstance();
        CitiesDataType csvType = new CitiesDataType(DATATYPE, CSV_FIXTURE, fields);
        csvType.getHadoopConfiguration().set(DATATYPE + UID_FIELDS, CityField.EVENT_ID.name());
        Collection<DataTypeHadoopConfig> csvTypes = new ArrayList<>();
        csvTypes.add(csvType);
        csvSetup.setData(FileType.CSV, csvTypes);
        csvClient = csvSetup.loadTables(new QueryTestTableHelper(ParquetCsvEquivalenceQueryTest.class.getName() + "-csv", log));

        ParquetCitiesDataType parquetType = new ParquetCitiesDataType(DATATYPE, PARQUET_FIXTURE, fields);
        parquetType.getHadoopConfiguration().set(DATATYPE + UID_FIELDS, CityField.EVENT_ID.name());
        Collection<DataTypeHadoopConfig> parquetTypes = new ArrayList<>();
        parquetTypes.add(parquetType);
        parquetSetup.setData(FileType.PARQUET, parquetTypes);
        parquetClient = parquetSetup.loadTables(new QueryTestTableHelper(ParquetCsvEquivalenceQueryTest.class.getName() + "-parquet", log));

        client = csvClient;
    }

    public ParquetCsvEquivalenceQueryTest() {
        super(CitiesDataType.getManager());
    }

    @Test
    public void testEqualityQuery() throws Exception {
        assertSameResults(CityField.CITY.name() + " == 'paris'", 1);
    }

    @Test
    public void testAnyFieldAndRangeQuery() throws Exception {
        assertSameResults("_ANYFIELD_ == 'texas' && " + CityField.NUM.name() + " > 50", 1);
    }

    @Test
    public void testRegexAndNegationQuery() throws Exception {
        assertSameResults(CityField.CITY.name() + " =~ 'par.*' && " + CityField.CONTINENT.name() + " != 'europe'", 1);
    }

    @Test
    public void testReverseIndexQuery() throws Exception {
        assertSameResults(CityField.STATE.name() + " =~ '.*france'", 1);
    }

    @Test
    public void testCountryGroupedByCode() throws Exception {
        assertSameResults(CityField.COUNTRY.name() + " == 'france' || " + CityField.CODE.name() + " == 'usa'", 1);
    }

    private void assertSameResults(String query, int atLeast) throws Exception {
        Set<String> fromCsv = documents(csvClient, query);
        Set<String> fromParquet = documents(parquetClient, query);
        log.info("query[" + query + "] csv(" + fromCsv.size() + ") parquet(" + fromParquet.size() + ")");
        assertFalse(fromCsv.size() < atLeast, "query returned too few documents from the CSV ingest: " + fromCsv);
        assertEquals(fromCsv, fromParquet, "Parquet and CSV ingests returned different documents for: " + query);
    }

    /**
     * Run the query through the shard query logic and render each returned document as a canonical string of its field values. ORIG_FILE and LOAD_DATE are
     * ingest-time artifacts that legitimately differ between the two runs and are excluded.
     */
    private Set<String> documents(AccumuloClient c, String query) throws Exception {
        SimpleDateFormat ymd = new SimpleDateFormat("yyyyMMdd");
        Date start = ymd.parse("20150101");
        Date end = ymd.parse("20160101");

        QueryImpl q = new QueryImpl();
        q.setBeginDate(start);
        q.setEndDate(end);
        q.setQuery(query);
        q.setId(UUID.randomUUID());
        q.setPagesize(Integer.MAX_VALUE);
        q.setQueryAuthorizations(auths.toString());

        GenericQueryConfiguration config = this.logic.initialize(c, q, this.authSet);
        this.logic.setupQuery(config);

        KryoDocumentDeserializer deserializer = new KryoDocumentDeserializer();
        Set<String> rendered = new TreeSet<>();
        Iterator<Map.Entry<Key,Value>> iter = this.logic.iterator();
        while (iter.hasNext()) {
            Map.Entry<Key,Value> entry = iter.next();
            if (FinalDocumentTrackingIterator.isFinalDocumentKey(entry.getKey())) {
                continue;
            }
            Document document = deserializer.apply(entry).getValue();
            TreeMap<String,TreeSet<String>> fields = new TreeMap<>();
            for (Map.Entry<String,Attribute<? extends Comparable<?>>> field : document.getDictionary().entrySet()) {
                String name = field.getKey();
                if (name.equals("ORIG_FILE") || name.equals("LOAD_DATE")) {
                    continue;
                }
                TreeSet<String> values = fields.computeIfAbsent(name, k -> new TreeSet<>());
                Attribute<?> attr = field.getValue();
                if (attr instanceof Attributes) {
                    for (Attribute<?> a : ((Attributes) attr).getAttributes()) {
                        values.add(String.valueOf(a.getData()));
                    }
                } else {
                    values.add(String.valueOf(attr.getData()));
                }
            }
            rendered.add(fields.toString());
        }
        return rendered;
    }
}
