package emissary.output.filter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.esotericsoftware.kryo.io.Output;
import emissary.config.ConfigUtil;
import emissary.config.Configurator;
import emissary.core.IBaseDataObject;
import emissary.output.DropOffPlace;
import emissary.output.kryo.EmissaryKryoFactory;

/**
 * Filter that writes out Kryo
 */
public class KryoOutputFilter extends AbstractRollableFilter {

    protected final EmissaryKryoFactory kryos = new EmissaryKryoFactory();
    private final ALThreadLocal lists = new ALThreadLocal();

    @Override
    public byte[] convert(List<IBaseDataObject> list, Map<String, Object> params) throws IOException {
        return new byte[0];
    }

    /**
     * Initialize reads the configuration items for this filter
     * 
     * @param configG the configurator to read from
     * @param filterName the configured name of this filter or null for the default
     * @param filterConfig the configuration for the specific filter
     */
    @Override
    public void initialize(Configurator configG, String filterName, Configurator filterConfig) {
        if (filterName == null) {
            setFilterName("KRYO");
        }
        super.initialize(configG, filterName, filterConfig);
        this.appendNewLine = false;
    }

    @Override
    public int filter(final List<IBaseDataObject> list, final Map<String, Object> params, final OutputStream ko) {
        final ArrayList<IBaseDataObject> l = prep(list);
        try {
            Output output = new Output(ko);
            kryos.get().writeObject(output, l);
            output.flush();
        } catch (Exception ex) {
            logger.error("IOException during KryoOutput", ex);
            return STATUS_FAILURE;
        }
        return STATUS_SUCCESS;
    }

    protected ArrayList<IBaseDataObject> prep(final List<IBaseDataObject> payloadList) {
        // the provided list is a Collections.synchronized wrapped list which is not registered with kryo
        final ArrayList<IBaseDataObject> l = lists.get();
        l.clear();
        l.addAll(payloadList);
        return l;
    }

    // here to limit object creation and GC
    private static class ALThreadLocal extends ThreadLocal<ArrayList<IBaseDataObject>> {
        @Override
        protected ArrayList<IBaseDataObject> initialValue() {
            return new ArrayList<>();
        }
    }

    /**
     * Main to test output types
     */
    public static void main(String[] args) throws IOException {
        String name = args.length > 0 ? args[0] : null;

        KryoOutputFilter filter = new KryoOutputFilter();
        try {
            Configurator config = ConfigUtil.getConfigInfo(DropOffPlace.class);
            filter.initialize(config, name);
            System.out.println("Output types " + filter.outputTypes);
        } catch (Exception ex) {
            System.err.println("Cannot configure filter: " + ex.getMessage());
        }
    }
}
