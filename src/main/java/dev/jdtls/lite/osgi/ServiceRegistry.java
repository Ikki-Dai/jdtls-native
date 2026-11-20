package dev.jdtls.lite.osgi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;

/**
 * Minimal OSGi service registry backing the emulation layer. Synchronous event
 * dispatch — good enough for the platform bundles' start-time ServiceTrackers.
 */
public final class ServiceRegistry {

    static final class Reg {
        final String[] classNames;
        final Object service;
        final Dictionary<String, Object> props;
        final FakeServiceReference ref;
        final FakeServiceRegistration reg;
        final ServiceRegistry registry;

        Reg(ServiceRegistry registry, String[] classNames, Object service, Dictionary<String, Object> props) {
            this.registry = registry;
            this.classNames = classNames.clone();
            this.service = service;
            this.props = props;
            this.ref = new FakeServiceReference(this);
            this.reg = new FakeServiceRegistration(this);
        }
    }

    private final Map<String, List<Reg>> byClass = new ConcurrentHashMap<>();
    private final List<Reg> all = new CopyOnWriteArrayList<>();
    private final List<Object[]> listeners = new CopyOnWriteArrayList<>(); // {ServiceListener, String filterString}

    public FakeServiceRegistration<?> registerService(String[] classNames, Object service, Dictionary<String, Object> props) {
        Dictionary<String, Object> p = props == null ? new Hashtable<>() : copyOf(props);
        List<Object> objectClass = new ArrayList<>();
        Collections.addAll(objectClass, classNames);
        p.put("objectClass", objectClass);
        Reg r = new Reg(this, classNames, service, p);
        for (String cn : classNames) {
            byClass.computeIfAbsent(cn, k -> new ArrayList<>()).add(r);
        }
        all.add(r);
        fire(r.ref, ServiceEvent.REGISTERED);
        return r.reg;
    }

    private static Dictionary<String, Object> copyOf(Dictionary<String, Object> src) {
        Dictionary<String, Object> copy = new Hashtable<>();
        for (Enumeration<String> e = src.keys(); e.hasMoreElements(); ) {
            String key = e.nextElement();
            copy.put(key, src.get(key));
        }
        return copy;
    }

    public FakeServiceReference<?> getServiceReference(String className) {
        List<Reg> list = byClass.get(className);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1).ref; // last registered wins (simple ranking)
    }

    public List<FakeServiceReference<?>> getServiceReferences(String className, org.osgi.framework.Filter filter) {
        List<FakeServiceReference<?>> result = new ArrayList<>();
        for (Reg r : all) {
            if (className != null && !isAssigned(r.classNames, className)) {
                continue;
            }
            if (filter != null && !filter.matches(asMap(r.props))) {
                continue;
            }
            result.add(r.ref);
        }
        return result;
    }

    /** Plain Map view (Hashtable implements both Dictionary and Map, which makes Filter.matches ambiguous). */
    private static Map<String, Object> asMap(Dictionary<String, Object> d) {
        Map<String, Object> m = new java.util.HashMap<>();
        for (Enumeration<String> e = d.keys(); e.hasMoreElements(); ) {
            String key = e.nextElement();
            m.put(key, d.get(key));
        }
        return m;
    }

    private static boolean isAssigned(String[] classNames, String className) {
        for (String cn : classNames) {
            if (cn.equals(className)) {
                return true;
            }
        }
        return false;
    }

    public <S> S getService(FakeServiceReference<S> ref) {
        return ref == null ? null : (S) ref.owner().service;
    }

    public void unregister(FakeServiceRegistration<?> reg) {
        Reg r = reg.owner();
        for (String cn : r.classNames) {
            List<Reg> list = byClass.get(cn);
            if (list != null) {
                list.remove(r);
                if (list.isEmpty()) {
                    byClass.remove(cn);
                }
            }
        }
        all.remove(r);
        fire(r.ref, ServiceEvent.UNREGISTERING);
    }

    public void addServiceListener(ServiceListener listener, String filterString) {
        listeners.add(new Object[] { listener, filterString });
    }

    public void removeServiceListener(ServiceListener listener) {
        listeners.removeIf(e -> e[0] == listener);
    }

    private void fire(FakeServiceReference<?> ref, int type) {
        if (listeners.isEmpty()) {
            return;
        }
        ServiceEvent event = new ServiceEvent(type, ref);
        for (Object[] entry : listeners) {
            ServiceListener l = (ServiceListener) entry[0];
            String filterString = (String) entry[1];
            try {
                if (filterString == null) {
                    l.serviceChanged(event);
                    continue;
                }
                org.osgi.framework.Filter f = FakeFilter.parse(filterString);
                if (f != null && f.match(ref)) {
                    l.serviceChanged(event);
                }
            } catch (RuntimeException e) {
                // never let a listener kill the boot
            } catch (org.osgi.framework.InvalidSyntaxException e) {
                // idem
            }
        }
    }

    public List<FakeServiceReference<?>> allReferences() {
        List<FakeServiceReference<?>> result = new ArrayList<>();
        for (Reg r : all) {
            result.add(r.ref);
        }
        return Collections.unmodifiableList(result);
    }
}
