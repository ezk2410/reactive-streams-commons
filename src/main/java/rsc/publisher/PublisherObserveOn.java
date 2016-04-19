package rsc.publisher;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import rsc.flow.Fuseable;
import rsc.flow.Loopback;
import rsc.flow.Producer;
import rsc.flow.Receiver;
import rsc.scheduler.Scheduler;
import rsc.scheduler.Scheduler.Worker;
import rsc.state.Backpressurable;
import rsc.state.Cancellable;
import rsc.state.Completable;
import rsc.state.Introspectable;
import rsc.state.Prefetchable;
import rsc.state.Requestable;
import rsc.util.BackpressureHelper;
import rsc.util.EmptySubscription;
import rsc.util.ExceptionHelper;
import rsc.util.SubscriptionHelper;

/**
 * Emits events on a different thread specified by a scheduler callback.
 *
 * @param <T> the value type
 */
public final class PublisherObserveOn<T> extends PublisherSource<T, T> implements Loopback {

    final Scheduler scheduler;
    
    final boolean delayError;
    
    final Supplier<? extends Queue<T>> queueSupplier;
    
    final int prefetch;
    
    public PublisherObserveOn(
            Publisher<? extends T> source, 
            Scheduler scheduler, 
            boolean delayError,
            int prefetch,
            Supplier<? extends Queue<T>> queueSupplier) {
        super(source);
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.delayError = delayError;
        this.prefetch = prefetch;
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        
        if (source instanceof Fuseable.ScalarCallable) {
            PublisherSubscribeOn.scalarScheduleOn(source, s, scheduler);
            return;
        }

        Worker worker;
        
        try {
            worker = scheduler.createWorker();
        } catch (Throwable e) {
            ExceptionHelper.throwIfFatal(e);
            EmptySubscription.error(s, e);
            return;
        }
        
        if (worker == null) {
            EmptySubscription.error(s, new NullPointerException("The scheduler returned a null Function"));
            return;
        }
        
        if (s instanceof Fuseable.ConditionalSubscriber) {
            Fuseable.ConditionalSubscriber<? super T> cs = (Fuseable.ConditionalSubscriber<? super T>) s;
            source.subscribe(new PublisherObserveOnConditionalSubscriber<>(cs, worker, delayError, prefetch, queueSupplier));
            return;
        }
        source.subscribe(new PublisherObserveOnSubscriber<>(s, worker, delayError, prefetch, queueSupplier));
    }


    @Override
    public Object connectedOutput() {
        return scheduler;
    }


    @Override
    public long getCapacity() {
        return prefetch;
    }

    static final class PublisherObserveOnSubscriber<T>
    implements Subscriber<T>, Subscription, Runnable, Producer, Loopback, Backpressurable, Prefetchable, Receiver, Cancellable,
               Introspectable,
               Requestable, Completable {
        
        final Subscriber<? super T> actual;
        
        final Worker worker;
        
        final boolean delayError;
        
        final int prefetch;
        
        final int limit;
        
        final Supplier<? extends Queue<T>> queueSupplier;
        
        Subscription s;
        
        Queue<T> queue;
        
        volatile boolean cancelled;
        
        volatile boolean done;
        
        Throwable error;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherObserveOnSubscriber> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherObserveOnSubscriber> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, "requested");

        int sourceMode;
        
        long produced;
        
        public PublisherObserveOnSubscriber(
                Subscriber<? super T> actual,
                Worker worker,
                boolean delayError,
                int prefetch,
                Supplier<? extends Queue<T>> queueSupplier) {
            this.actual = actual;
            this.worker = worker;
            this.delayError = delayError;
            this.prefetch = prefetch;
            this.queueSupplier = queueSupplier;
            if (prefetch != Integer.MAX_VALUE) {
                this.limit = prefetch - (prefetch >> 2);
            } else {
                this.limit = Integer.MAX_VALUE;
            }
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                if (s instanceof Fuseable.QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;
                    
                    int m = f.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);
                    
                    if (m == Fuseable.SYNC) {
                        sourceMode = Fuseable.SYNC;
                        queue = f;
                        done = true;
                        
                        actual.onSubscribe(this);
                        return;
                    } else
                    if (m == Fuseable.ASYNC) {
                        sourceMode = Fuseable.ASYNC;
                        queue = f;
                    } else {
                        try {
                            queue = queueSupplier.get();
                        } catch (Throwable e) {
                            ExceptionHelper.throwIfFatal(e);
                            s.cancel();
                            
                            try {
                                EmptySubscription.error(actual, e);
                            } finally {
                                worker.shutdown();
                            }
                            return;
                        }
                    }
                } else {
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        try {
                            EmptySubscription.error(actual, e);
                        } finally {
                            worker.shutdown();
                        }
                        return;
                    }
                }
                
                actual.onSubscribe(this);
                
                if (prefetch == Integer.MAX_VALUE) {
                    s.request(Long.MAX_VALUE);
                } else {
                    s.request(prefetch);
                }
            }
        }
        
        @Override
        public void onNext(T t) {
            if (sourceMode == Fuseable.ASYNC) {
                trySchedule();
                return;
            }
            if (!queue.offer(t)) {
                s.cancel();
                
                error = new IllegalStateException("Queue is full?!");
                done = true;
            }
            trySchedule();
        }
        
        @Override
        public void onError(Throwable t) {
            error = t;
            done = true;
            trySchedule();
        }
        
        @Override
        public void onComplete() {
            done = true;
            trySchedule();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
                trySchedule();
            }
        }
        
        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            
            cancelled = true;
            worker.shutdown();
            
            if (WIP.getAndIncrement(this) == 0) {
                s.cancel();
                queue.clear();
            }
        }

        void trySchedule() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            worker.schedule(this);
        }

        void runSync() {
            int missed = 1;

            final Subscriber<? super T> a = actual;
            final Queue<T> q = queue;

            long e = produced;

            for (;;) {

                long r = requested;

                while (e != r) {
                    T v;

                    try {
                        v = q.poll();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        doError(a, ex);
                        return;
                    }

                    if (cancelled) {
                        return;
                    }
                    if (v == null) {
                        doComplete(a);
                        return;
                    }

                    a.onNext(v);

                    e++;
                }

                if (e == r) {
                    if (cancelled) {
                        return;
                    }

                    boolean empty;

                    try {
                        empty = q.isEmpty();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        doError(a, ex);
                        return;
                    }

                    if (empty) {
                        doComplete(a);
                        return;
                    }
                }

                int w = wip;
                if (missed == w) {
                    produced = e;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }

        void runAsync() {
            int missed = 1;

            final Subscriber<? super T> a = actual;
            final Queue<T> q = queue;

            long e = produced;

            for (;;) {

                long r = requested;

                while (e != r) {
                    boolean d = done;
                    T v;

                    try {
                        v = q.poll();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);

                        s.cancel();
                        q.clear();

                        doError(a, ex);
                        return;
                    }

                    boolean empty = v == null;

                    if (checkTerminated(d, empty, a)) {
                        return;
                    }

                    if (empty) {
                        break;
                    }

                    a.onNext(v);

                    e++;
                    if (e == limit) {
                        if (r != Long.MAX_VALUE) {
                            r = REQUESTED.addAndGet(this, -e);
                        }
                        s.request(e);
                        e = 0L;
                    }
                }

                if (e == r) {
                    boolean d = done;
                    boolean empty;
                    try {
                        empty = q.isEmpty();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);

                        s.cancel();
                        q.clear();

                        doError(a, ex);
                        return;
                    }

                    if (checkTerminated(d, empty, a)) {
                        return;
                    }
                }

                int w = wip;
                if (missed == w) {
                    produced = e;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }

        void doComplete(Subscriber<?> a) {
            try {
                a.onComplete();
            } finally {
                worker.shutdown();
            }
        }
        
        void doError(Subscriber<?> a, Throwable e) {
            try {
                a.onError(e);
            } finally {
                worker.shutdown();
            }
        }
        
        @Override
        public void run() {
            if (sourceMode == Fuseable.SYNC) {
                runSync();
            } else {
                runAsync();
            }
        }

        boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
            if (cancelled) {
                s.cancel();
                queue.clear();
                return true;
            }
            if (d) {
                if (delayError) {
                    if (empty) {
                        Throwable e = error;
                        if (e != null) {
                            doError(a, e);
                        } else {
                            doComplete(a);
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        queue.clear();
                        doError(a, e);
                        return true;
                    } else
                    if (empty) {
                        doComplete(a);
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public long requestedFromDownstream() {
            return queue == null ? prefetch : (prefetch - queue.size());
        }

        @Override
        public long getCapacity() {
            return prefetch;
        }

        @Override
        public long getPending() {
            return queue != null ? queue.size() : -1L;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isStarted() {
            return s != null && !cancelled && !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Throwable getError() {
            return error;
        }

        @Override
        public Object connectedInput() {
            return null;
        }

        @Override
        public Object connectedOutput() {
            return worker;
        }

        @Override
        public long expectedFromUpstream() {
            return queue == null ? prefetch : (prefetch - queue.size());
        }

        @Override
        public long limit() {
            return limit;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object upstream() {
            return s;
        }
    }

    static final class PublisherObserveOnConditionalSubscriber<T>
    implements Subscriber<T>, Subscription, Runnable,
               Producer, Loopback, Backpressurable, Prefetchable, Receiver, Cancellable, Introspectable, Completable, Requestable {
        
        final Fuseable.ConditionalSubscriber<? super T> actual;
        
        final Worker worker;
        
        final boolean delayError;
        
        final int prefetch;
        
        final int limit;

        final Supplier<? extends Queue<T>> queueSupplier;
        
        Subscription s;
        
        Queue<T> queue;
        
        volatile boolean cancelled;
        
        volatile boolean done;
        
        Throwable error;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherObserveOnConditionalSubscriber> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherObserveOnConditionalSubscriber.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherObserveOnConditionalSubscriber> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublisherObserveOnConditionalSubscriber.class, "requested");

        int sourceMode;
        
        long produced;
        
        long consumed;
        
        public PublisherObserveOnConditionalSubscriber(
                Fuseable.ConditionalSubscriber<? super T> actual,
                Worker worker,
                boolean delayError,
                int prefetch,
                Supplier<? extends Queue<T>> queueSupplier) {
            this.actual = actual;
            this.worker = worker;
            this.delayError = delayError;
            this.prefetch = prefetch;
            this.queueSupplier = queueSupplier;
            if (prefetch != Integer.MAX_VALUE) {
                this.limit = prefetch - (prefetch >> 2);
            } else {
                this.limit = Integer.MAX_VALUE;
            }
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                if (s instanceof Fuseable.QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;
                    
                    int m = f.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);
                    
                    if (m == Fuseable.SYNC) {
                        sourceMode = Fuseable.SYNC;
                        queue = f;
                        done = true;
                        
                        actual.onSubscribe(this);
                        return;
                    } else
                    if (m == Fuseable.ASYNC) {
                        sourceMode = Fuseable.ASYNC;
                        queue = f;
                    } else {
                        try {
                            queue = queueSupplier.get();
                        } catch (Throwable e) {
                            ExceptionHelper.throwIfFatal(e);
                            s.cancel();
                            try {
                                EmptySubscription.error(actual, e);
                            } finally {
                                worker.shutdown();
                            }
                            return;
                        }
                    }
                } else {
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        
                        try {
                            EmptySubscription.error(actual, e);
                        } finally {
                            worker.shutdown();
                        }

                        return;
                    }
                }
                
                actual.onSubscribe(this);
                
                if (prefetch == Integer.MAX_VALUE) {
                    s.request(Long.MAX_VALUE);
                } else {
                    s.request(prefetch);
                }
            }
        }
        
        @Override
        public void onNext(T t) {
            if (sourceMode == Fuseable.ASYNC) {
                trySchedule();
                return;
            }
            if (!queue.offer(t)) {
                s.cancel();
                
                error = new IllegalStateException("Queue is full?!");
                done = true;
            }
            trySchedule();
        }
        
        @Override
        public void onError(Throwable t) {
            error = t;
            done = true;
            trySchedule();
        }
        
        @Override
        public void onComplete() {
            done = true;
            trySchedule();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
                trySchedule();
            }
        }
        
        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            
            cancelled = true;
            worker.shutdown();
            
            if (WIP.getAndIncrement(this) == 0) {
                s.cancel();
                queue.clear();
            }
        }
        
        void trySchedule() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            
            worker.schedule(this);
        }
        
        void runSync() {
            int missed = 1;
            
            final Fuseable.ConditionalSubscriber<? super T> a = actual;
            final Queue<T> q = queue;

            long e = produced;

            for (;;) {
                
                long r = requested;
                
                while (e != r) {
                    T v;
                    try {
                        v = q.poll();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        doError(a, ex);
                        return;
                    }

                    if (cancelled) {
                        return;
                    }
                    if (v == null) {
                        doComplete(a);
                        return;
                    }
                    
                    if (a.tryOnNext(v)) {
                        e++;
                    }
                }
                
                if (e == r) {
                    if (cancelled) {
                        return;
                    }
                    
                    boolean empty;
                    
                    try {
                        empty = q.isEmpty();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        doError(a, ex);
                        return;
                    }
                    
                    if (empty) {
                        doComplete(a);
                        return;
                    }
                }

                int w = wip;
                if (missed == w) {
                    produced = e;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
        
        void runAsync() {
            int missed = 1;
            
            final Fuseable.ConditionalSubscriber<? super T> a = actual;
            final Queue<T> q = queue;
            
            long emitted = produced;
            long polled = consumed;
            
            for (;;) {
                
                long r = requested;
                
                while (emitted != r) {
                    boolean d = done;
                    T v;
                    try {
                        v = q.poll();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);

                        s.cancel();
                        q.clear();
                        
                        doError(a, ex);
                        return;
                    }
                    boolean empty = v == null;
                    
                    if (checkTerminated(d, empty, a)) {
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }

                    if (a.tryOnNext(v)) {
                        emitted++;
                    }
                    
                    polled++;
                    
                    if (polled == limit) {
                        s.request(polled);
                        polled = 0L;
                    }
                }
                
                if (emitted == r) {
                    boolean d = done;
                    boolean empty;
                    try {
                        empty = q.isEmpty();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);

                        s.cancel();
                        q.clear();
                        
                        doError(a, ex);
                        return;
                    }

                    if (checkTerminated(d, empty, a)) {
                        return;
                    }
                }
                
                int w = wip;
                if (missed == w) {
                    produced = emitted;
                    consumed = polled;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }

        }
        
        @Override
        public void run() {
            if (sourceMode == Fuseable.SYNC) {
                runSync();
            } else {
                runAsync();
            }
        }

        @Override
        public long getCapacity() {
            return prefetch;
        }

        @Override
        public long getPending() {
            return queue != null ? queue.size() : -1;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isStarted() {
            return s != null && !cancelled && !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Throwable getError() {
            return error;
        }

        @Override
        public Object connectedInput() {
            return null;
        }

        @Override
        public Object connectedOutput() {
            return worker;
        }

        @Override
        public long expectedFromUpstream() {
            return queue == null ? prefetch : (prefetch - queue.size());
        }

        @Override
        public long limit() {
            return limit;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object upstream() {
            return s;
        }

        @Override
        public long requestedFromDownstream() {
            return queue == null ? requested : (requested - queue.size());
        }

        void doComplete(Subscriber<?> a) {
            try {
                a.onComplete();
            } finally {
                worker.shutdown();
            }
        }
        
        void doError(Subscriber<?> a, Throwable e) {
            try {
                a.onError(e);
            } finally {
                worker.shutdown();
            }
        }
        
        boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
            if (cancelled) {
                s.cancel();
                queue.clear();
                return true;
            }
            if (d) {
                if (delayError) {
                    if (empty) {
                        Throwable e = error;
                        if (e != null) {
                            doError(a, e);
                        } else {
                            doComplete(a);
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        queue.clear();
                        doError(a, e);
                        return true;
                    } else 
                    if (empty) {
                        doComplete(a);
                        return true;
                    }
                }
            }
            
            return false;
        }
    }
}
