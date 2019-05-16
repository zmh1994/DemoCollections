import groovy.util.logging.Slf4j;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import io.reactivex.internal.operators.observable.ObservableCreate;
import io.reactivex.internal.operators.observable.ObservableObserveOn;
import io.reactivex.observers.TestObserver;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.SchedulerRunnableIntrospection;
import io.reactivex.schedulers.Schedulers;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.val;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.lang.ThreadLocal.withInitial;
import static java.util.Collections.synchronizedList;
import static java.util.Collections.synchronizedMap;
import static java.util.concurrent.CompletableFuture.runAsync;
import static org.junit.Assert.assertEquals;


class OnObservableCreateAction implements BiFunction<Observable, Observer, Observer> {

    @SuppressWarnings("unchecked")
    public Observer apply(Observable observable, Observer observer) {
        if (observable instanceof ObservableObserveOn) {
            return new RunInContextObserver(observer);
        } else if (observable instanceof ObservableCreate) {
            return new RunInContextObserver(observer);
        }
        return observer;
    }

}

class RunInContextObserver<T> implements Observer<T> {

    private final Observer<T> source;
    private volatile ContextState state = null;

    public RunInContextObserver(Observer<T> observer) {
        this.source = observer;
    }

    @Override
    public void onComplete() {
        try (val ignore = RxContextManager.withContext(state)) {
            source.onComplete();
        }
    }

    @Override
    public void onError(Throwable t) {
        try (val ignore = RxContextManager.withContext(state)) {
            source.onError(t);
        }
    }

    @Override
    public void onNext(T v) {
        try (val ignore = RxContextManager.withContext(state)) {
            source.onNext(v);
        }
    }

    @Override
    public void onSubscribe(Disposable d) {
        this.state = RxContextManager.captureContext();
        source.onSubscribe(d);
    }

}

class ScheduleHandlerAction implements Function<Runnable, Runnable> {

    @Override
    public Runnable apply(Runnable runnable) {
        if (runnable instanceof RunInContextRunable) {
            return runnable;
        }
        if (
            (runnable instanceof SchedulerRunnableIntrospection)
                && ((SchedulerRunnableIntrospection) runnable).getWrappedRunnable() instanceof RunInContextRunable
            ) {
            return runnable;
        }
        System.out.println("schedule wrapping.....................");
        return new RunInContextRunable(runnable);

    }

    static class RunInContextRunable implements Runnable {
        private final Runnable runnable;
        private final ContextState state;

        RunInContextRunable(Runnable runnable) {
            this.runnable = runnable;
            this.state = RxContextManager.captureContext();
        }

        @Override
        public void run() {
            try (val ignore = RxContextManager.withContext(state)) {
                runnable.run();
            }
        }
    }
}

interface ContextScope extends Closeable {
    void close();
}

@Value
class DefaultContextScope implements ContextScope {

    private final ContextState prev;
    private final AtomicBoolean isRestore = new AtomicBoolean(false);

    @Override
    public void close() {
        if (isRestore.compareAndSet(false, true)) {
            RxContextManager.restore(prev);
        }
    }
}

@Value
class ContextState {
    @NonNull
    private final Object[] state;
}

class RxContextManager {

    public static void restore(ContextState state) {
        if (state != null) {
            DataStoreContext.setContext(state.getState()[0]);
            DomainEventManager.setContext(state.getState()[1]);
        } else {
            DataStoreContext.clearContext();
            DomainEventManager.clearContext();
        }
    }

    public static ContextState captureContext() {
        return new ContextState(new Object[]{
            DataStoreContext.getContext(),
            DomainEventManager.getContext()
        });
    }

    public static ContextScope withContext(ContextState state) {
        ContextState prevState = captureContext();
        restore(state);
        return new DefaultContextScope(prevState);
    }
}

// DataStore Context
class DataStoreContext {
    private final static ThreadLocal<Map<String, String>> THREAD_LOCAL = withInitial(() -> synchronizedMap(new HashMap<String, String>()));

    public static Object getContext() {
        return THREAD_LOCAL.get();
    }

    public static void setContext(Object context) {
        THREAD_LOCAL.set((Map<String, String>) context);
    }


    public static void clearContext() {
        THREAD_LOCAL.remove();
    }


    public static String get(String key) {
        return THREAD_LOCAL.get().get(key);
    }

    public static void put(String key, String value) {
        THREAD_LOCAL.get().put(key, value);
    }

    public static void clear() {
        THREAD_LOCAL.get().clear();
    }

}

class DomainEventManager {
    private final static ThreadLocal<List<Consumer>> THREAD_LOCAL = withInitial(() -> synchronizedList(new ArrayList<>()));

    public static void register(Consumer handler) {
        THREAD_LOCAL.get().add(handler);
    }

    public static void send(Object event) {
        THREAD_LOCAL.get().forEach(h -> h.accept(event));
    }

    public static Object getContext() {
        return THREAD_LOCAL.get();
    }

    public static void setContext(Object context) {
        THREAD_LOCAL.set((List<Consumer>) context);
    }


    public static void clearContext() {
        THREAD_LOCAL.remove();
    }
}


@Slf4j
public class DemoApplicationTest {

    @BeforeClass
    public static void init() {
        RxJavaPlugins.setOnObservableSubscribe(new OnObservableCreateAction());
        RxJavaPlugins.setScheduleHandler(new ScheduleHandlerAction());
    }

    protected static void assertContextIsTest() {
        assertContextIs("test");
    }

    protected static void assertContextIs(String expect) {
        Object id = DataStoreContext.get("id");
        System.out.println("current context is " + id);
        assertEquals(expect, id);
    }

    @Before
    public void before() {
        System.out.println("put (id, test) to context");
        DataStoreContext.put("id", "test");
        assertContextIsTest();
    }

    @After
    public void after() {
        DataStoreContext.clear();
    }

    @Test
    @SneakyThrows
    public void testObservable() {
        Observable<String> observable = Observable
            .<String>create(emitter -> {
                assertContextIsTest();
                emitter.onNext("a");

                runAsync(() -> {
                    emitter.onNext("b");
                });
                emitter.onNext("a");
                emitter.onComplete();
            })
            .subscribeOn(Schedulers.io())
            .map(s -> "A:" + s)
            .doOnNext(i -> assertContextIsTest());

        TestObserver<String> test = observable.test();

        TestObserver<String> test2 = observable.test();

        test.await()
            .assertNoErrors()
            .assertComplete();
        test2.await()
            .assertNoErrors()
            .assertComplete();


    }

    @Test
    public void test2() {
        Observable
            .<Integer>create(e -> {
                e.onNext(1);
                e.onNext(2);
                e.onComplete();
            })
            .map(i -> i * 2)
            .map(i -> Observable.create(e -> {
                for (int j = 0; j < i; j++) {
                    e.onNext(j);
                }
                e.onComplete();
            }))
            .subscribe(i -> System.out.println(i));
    }

    @Test
    public void testSingleDemo() {
        Single
            .<Integer>create(e -> {
                System.out.println("created!");
                e.onSuccess(1);
            })
            .map(i -> i * 2)
            .subscribe(i -> System.out.println(i));
    }

    @Test
    public void testSingleDeferDemo() {
        Single<Integer> single = Single
            .defer(() -> {
                DomainEventManager.register(event -> {
                    System.out.println("receive event: " + event);
                    System.out.println("send event to mq:" + event);
                });
                System.out.println("init context before create: " + Thread.currentThread().getId());
                return getSingle();
            })
            .map(i -> i * 2)
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .map(i -> i + 1);

        single.subscribe(i -> System.out.println(i));

        single.subscribe(i -> System.out.println(i));

    }

    private Single<Integer> getSingle() {
        System.out.println("current thread id:" + Thread.currentThread().getId());
        return Single
            .<Integer>create(e -> {
                System.out.println("created!");
                e.onSuccess(1);
            })
            .doOnSuccess(i -> DomainEventManager.send("evnt-" + i))
            ;
    }

}