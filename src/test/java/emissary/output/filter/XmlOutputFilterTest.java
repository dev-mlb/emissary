package emissary.output.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import emissary.config.ServiceConfigGuide;
import emissary.core.DataObjectFactory;
import emissary.core.IBaseDataObject;
import emissary.test.core.UnitTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class XmlOutputFilterTest extends UnitTest {

    private ServiceConfigGuide config;
    private IBaseDataObject payload;
    private IDropOffFilter f;
    private Path tmpDir;

    @Before
    public void setup() throws IOException {
        tmpDir = java.nio.file.Files.createTempDirectory(null);

        config = new ServiceConfigGuide();
        config.removeAllEntries("OUTPUT_PATH");
        config.addEntry("OUTPUT_SPEC_FOO", "/tmp/%S%.%F%");
        config.addEntry("OUTPUT_SPEC_BAR", "/xyzzy/%S%.%F%");
        config.addEntry("OUTPUT_PATH", tmpDir.toAbsolutePath().toString());

        f = new XmlOutputFilter();

        payload = DataObjectFactory.getInstance();
        payload.setData("This is the data".getBytes());
        payload.setFileType("FTYPE");
        payload.setFilename("/this/is/a/testfile");
    }

    @After
    public void tearDown() throws IOException {
        Files.deleteIfExists(tmpDir);
        config = null;
    }

    @Test
    public void testFilterSetup() {
        f.initialize(config, "FOO");
        assertEquals("Filter name should be set", "FOO", f.getFilterName());
        assertEquals("Output spec should be build based on name", "/tmp/%S%.%F%", f.getOutputSpec());
    }

    @Test
    public void testOutputFromFilter() {
        f.initialize(config, "FOO", config);
        List<IBaseDataObject> payloadList = Lists.newArrayList(payload);

        Map<String, Object> params = new HashMap<String, Object>();

        assertTrue("Payload should be outputtable", f.isOutputtable(payload, params));
        assertTrue("Payload list should be outputtable", f.isOutputtable(payloadList, params));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = f.filter(payloadList, params, output);

        assertEquals("Status of filter should be success", IDropOffFilter.STATUS_SUCCESS, status);
        assertTrue("Output must contain name field '" + output + "'", output.toString().indexOf("<name>/this/is/a/testfile</name>") > -1);
    }

}
