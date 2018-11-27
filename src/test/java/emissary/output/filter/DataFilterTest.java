package emissary.output.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import emissary.core.EmissaryException;
import org.junit.Test;

import emissary.config.Configurator;
import emissary.config.ServiceConfigGuide;
import emissary.core.DataObjectFactory;
import emissary.core.IBaseDataObject;
import emissary.test.core.UnitTest;
import emissary.util.shell.Executrix;

public class DataFilterTest extends UnitTest {

    @Test
    public void testFilterSetup() throws EmissaryException {
        Configurator config = new ServiceConfigGuide();
        config.addEntry("OUTPUT_SPEC_FOO", "/tmp/%S%.%F%");
        config.addEntry("OUTPUT_SPEC_BAR", "/xyzzy/%S%.%F%");

        Configurator filterConfig = new ServiceConfigGuide();
        filterConfig.addEntry("OUTPUT_PATH", "/tmp/data");

        IDropOffFilter f = new DataFilter();
        f.initialize(config, "FOO", filterConfig);
        assertEquals("Filter name should be set", "FOO", f.getFilterName());
        assertEquals("Output spec should be build based on name", "/tmp/%S%.%F%", f.getOutputSpec());
        assertEquals("Output path should be set", "/tmp/data", f.getOutputPath().toString());
    }

    @Test
    public void testOutputFromFilter() throws EmissaryException {
        Configurator config = new ServiceConfigGuide();
        config.addEntry("OUTPUT_SPEC_FOO", "/tmp/%S%.%F%");
        config.addEntry("OUTPUT_TYPE", "FTYPE.PrimaryView");
        config.addEntry("OUTPUT_PATH", "/tmp/data");

        IDropOffFilter f = new DataFilter();
        f.initialize(config, "FOO", config);

        IBaseDataObject payload = DataObjectFactory.getInstance();
        payload.setData("This is the data".getBytes());
        payload.setFileType("FTYPE");
        payload.setFilename("/this/is/a/testfile");
        List<IBaseDataObject> payloadList = new ArrayList<IBaseDataObject>();
        payloadList.add(payload);

        Map<String, Object> params = new HashMap<String, Object>();

        int status = f.filter(payload, params);

        File expected = new File("/tmp/testfile.FTYPE");
        assertEquals("Status of filter should be success", IDropOffFilter.STATUS_SUCCESS, status);
        assertTrue("Output File should exist", expected.exists());

        String output = new String(Executrix.readDataFromFile("/tmp/testfile.FTYPE"));
        assertEquals("Output must be the payload and nothing else", new String(payload.data()), output);

        expected.delete();
    }

}
