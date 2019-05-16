import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.runAsync;

/**
 * @author zmh
 */
public class DemoApplication {
    @SneakyThrows
    public static void main(String[] args) {
        ServiceFactory.registerApplies(ServiceContextWrapper::new);

        Context.setCompanyId(1L);
        Service s1 = ServiceFactory.createService();

        Context.setCompanyId(2L);
        Service s2 = ServiceFactory.createService();

        Context.setCompanyId(3L);
        Service s3 = ServiceFactory.createService();

        List<Service> services = Arrays.asList(s1, s2, s3);


        CompletableFuture<Void> a1 = runAsync(() -> services.forEach(Service::hi));

        a1.join();
    }
}

class ServiceFactory {
    private static final List<Function<Service, Service>> onServiceCreate = new ArrayList<>();

    static Service createService() {
        return apply(new ServiceImpl());
    }

    private static Service apply(Service service) {
        for (val s : onServiceCreate) {
            service = s.apply(service);
        }
        return service;
    }

    public static void registerApplies(Function<Service, Service> a) {
        onServiceCreate.add(a);
    }

}

// context decorator
@RequiredArgsConstructor
class ServiceContextWrapper implements Service {
    @NonNull
    private final Service service;
    private final Long context = Context.getCompanyId();

    @Override
    public void hi() {
        try (ContextHolder ignored = ContextHolder.withContext(context)) {
            service.hi();
        }
    }

}

class ContextHolder implements Closeable {
    private final Long prevId;

    private ContextHolder(Long id) {
        this.prevId = Context.getCompanyId();
        Context.setCompanyId(id);
    }

    static ContextHolder withContext(Long id) {
        return new ContextHolder(id);
    }

    @Override
    public void close() {
        Context.setCompanyId(prevId);
    }
}

@Slf4j
class ServiceImpl implements Service {

    @Override
    @SneakyThrows
    public void hi() {
        TimeUnit.SECONDS.sleep(1);
        Long id = Context.getCompanyId();
        log.info("company id is {}", id);
    }
}

interface Service {
    void hi();
}

// ThreadLocal  pattern
// Context

@Slf4j
class Context {
    private final static ThreadLocal<Long> context = new ThreadLocal<>();

    public static Long getCompanyId() {
        return context.get();
    }

    public static Long setCompanyId(Long id) {
        Long prevCompanyId = getCompanyId();
        context.set(id);
        return prevCompanyId;
    }

}
