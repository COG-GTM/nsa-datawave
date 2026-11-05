package datawave.query.testframework;

import java.io.IOException;
import java.net.URISyntaxException;

import org.apache.log4j.Logger;

import datawave.data.type.GeoType;
import datawave.data.type.LcNoDiacriticsType;
import datawave.data.type.NumberType;
import datawave.ingest.data.config.CSVHelper;
import datawave.ingest.data.config.ingest.BaseIngestHelper;
import datawave.ingest.input.reader.EventRecordReader;
import datawave.query.testframework.CitiesDataType.CityField;

/**
 * The cities datatype backed by a Parquet file instead of a CSV file. Configured identically to {@link CitiesDataType} so that a Parquet fixture carrying the
 * same records as a cities CSV fixture produces the same shard and index entries.
 */
public class ParquetCitiesDataType extends AbstractDataTypeConfig {

    private static final Logger log = Logger.getLogger(ParquetCitiesDataType.class);

    public ParquetCitiesDataType(final String city, final String ingestFile, final FieldConfig config) throws IOException, URISyntaxException {
        super(city, ingestFile, FileType.PARQUET, config, CityDataManager.getInstance());

        this.hConf.set(this.dataType + "." + CityField.NUM.name() + BaseIngestHelper.FIELD_TYPE, NumberType.class.getName());
        this.hConf.set(this.dataType + EventRecordReader.Properties.EVENT_DATE_FIELD_NAME, CityField.START_DATE.name());
        this.hConf.set(this.dataType + EventRecordReader.Properties.EVENT_DATE_FIELD_FORMAT, DATE_FIELD_FORMAT);
        this.hConf.set(this.dataType + CSVHelper.DATA_HEADER, String.join(",", CityField.headers()));
        this.hConf.set(this.dataType + "." + CityField.CODE.name() + BaseIngestHelper.FIELD_TYPE, LcNoDiacriticsType.class.getName());
        this.hConf.set(this.dataType + "." + CityField.GEO.name() + BaseIngestHelper.FIELD_TYPE, GeoType.class.getName());

        log.debug(this.toString());
    }
}
