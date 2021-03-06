/**
 * Copyright 2013 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.operators;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import rx.Observable;
import rx.Observable.OnSubscribeFunc;
import rx.Observer;
import rx.Subscription;
import rx.concurrency.TestScheduler;
import rx.subscriptions.Subscriptions;
import rx.util.functions.Action0;
import rx.util.functions.Func1;

/**
 * Transforms an Observable that emits Observables into a single Observable that emits the items
 * emitted by the most recently published of those Observables.
 * <p>
 * <img width="640" src="https://github.com/Netflix/RxJava/wiki/images/rx-operators/switchDo.png">
 */
public final class OperationSwitch {

    /**
     * This function transforms an {@link Observable} sequence of {@link Observable} sequences into a single {@link Observable} sequence which produces values from the most recently published
     * {@link Observable}.
     * 
     * @param sequences
     *            The {@link Observable} sequence consisting of {@link Observable} sequences.
     * @return A {@link Func1} which does this transformation.
     */
    public static <T> OnSubscribeFunc<T> switchDo(final Observable<? extends Observable<? extends T>> sequences) {
        return new OnSubscribeFunc<T>() {
            @Override
            public Subscription onSubscribe(Observer<? super T> observer) {
                return new Switch<T>(sequences).onSubscribe(observer);
            }
        };
    }

    private static class Switch<T> implements OnSubscribeFunc<T> {

        private final Observable<? extends Observable<? extends T>> sequences;

        public Switch(Observable<? extends Observable<? extends T>> sequences) {
            this.sequences = sequences;
        }

        @Override
        public Subscription onSubscribe(Observer<? super T> observer) {
            SafeObservableSubscription subscription = new SafeObservableSubscription();
            subscription.wrap(sequences.subscribe(new SwitchObserver<T>(observer, subscription)));
            return subscription;
        }
    }

    private static class SwitchObserver<T> implements Observer<Observable<? extends T>> {

        private final Observer<? super T> observer;
        private final SafeObservableSubscription parent;
        private final AtomicReference<Subscription> subsequence = new AtomicReference<Subscription>();

        public SwitchObserver(Observer<? super T> observer, SafeObservableSubscription parent) {
            this.observer = observer;
            this.parent = parent;
        }

        @Override
        public void onCompleted() {
            unsubscribeFromSubSequence();
            observer.onCompleted();
        }

        @Override
        public void onError(Throwable e) {
            unsubscribeFromSubSequence();
            observer.onError(e);
        }

        @Override
        public void onNext(Observable<? extends T> args) {
            unsubscribeFromSubSequence();

            subsequence.set(args.subscribe(new Observer<T>() {
                @Override
                public void onCompleted() {
                    // Do nothing.
                }

                @Override
                public void onError(Throwable e) {
                    parent.unsubscribe();
                    observer.onError(e);
                }

                @Override
                public void onNext(T args) {
                    observer.onNext(args);
                }
            }));
        }

        private void unsubscribeFromSubSequence() {
            Subscription previousSubscription = subsequence.get();
            if (previousSubscription != null) {
                previousSubscription.unsubscribe();
            }
        }
    }

    public static class UnitTest {

        private TestScheduler scheduler;
        private Observer<String> observer;

        @Before
        @SuppressWarnings("unchecked")
        public void before() {
            scheduler = new TestScheduler();
            observer = mock(Observer.class);
        }

        @Test
        public void testSwitchWithComplete() {
            Observable<Observable<String>> source = Observable.create(new OnSubscribeFunc<Observable<String>>() {
                @Override
                public Subscription onSubscribe(Observer<? super Observable<String>> observer) {
                    publishNext(observer, 50, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 50, "one");
                            publishNext(observer, 100, "two");
                            return Subscriptions.empty();
                        }
                    }));

                    publishNext(observer, 200, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 0, "three");
                            publishNext(observer, 100, "four");
                            return Subscriptions.empty();
                        }
                    }));

                    publishCompleted(observer, 250);

                    return Subscriptions.empty();
                }
            });

            Observable<String> sampled = Observable.create(OperationSwitch.switchDo(source));
            sampled.subscribe(observer);

            InOrder inOrder = inOrder(observer);

            scheduler.advanceTimeTo(90, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext(anyString());
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(125, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("one");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(175, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("two");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(225, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("three");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(350, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext(anyString());
            verify(observer, times(1)).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));
        }

        @Test
        public void testSwitchWithError() {
            Observable<Observable<String>> source = Observable.create(new OnSubscribeFunc<Observable<String>>() {
                @Override
                public Subscription onSubscribe(Observer<? super Observable<String>> observer) {
                    publishNext(observer, 50, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 50, "one");
                            publishNext(observer, 100, "two");
                            return Subscriptions.empty();
                        }
                    }));

                    publishNext(observer, 200, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 0, "three");
                            publishNext(observer, 100, "four");
                            return Subscriptions.empty();
                        }
                    }));

                    publishError(observer, 250, new TestException());

                    return Subscriptions.empty();
                }
            });

            Observable<String> sampled = Observable.create(OperationSwitch.switchDo(source));
            sampled.subscribe(observer);

            InOrder inOrder = inOrder(observer);

            scheduler.advanceTimeTo(90, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext(anyString());
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(125, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("one");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(175, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("two");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(225, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("three");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(350, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext(anyString());
            verify(observer, never()).onCompleted();
            verify(observer, times(1)).onError(any(TestException.class));
        }

        @Test
        public void testSwitchWithSubsequenceComplete() {
            Observable<Observable<String>> source = Observable.create(new OnSubscribeFunc<Observable<String>>() {
                @Override
                public Subscription onSubscribe(Observer<? super Observable<String>> observer) {
                    publishNext(observer, 50, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 50, "one");
                            publishNext(observer, 100, "two");
                            return Subscriptions.empty();
                        }
                    }));

                    publishNext(observer, 130, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishCompleted(observer, 0);
                            return Subscriptions.empty();
                        }
                    }));

                    publishNext(observer, 150, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 50, "three");
                            return Subscriptions.empty();
                        }
                    }));

                    return Subscriptions.empty();
                }
            });

            Observable<String> sampled = Observable.create(OperationSwitch.switchDo(source));
            sampled.subscribe(observer);

            InOrder inOrder = inOrder(observer);

            scheduler.advanceTimeTo(90, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext(anyString());
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(125, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("one");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(250, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("three");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));
        }

        @Test
        public void testSwitchWithSubsequenceError() {
            Observable<Observable<String>> source = Observable.create(new OnSubscribeFunc<Observable<String>>() {
                @Override
                public Subscription onSubscribe(Observer<? super Observable<String>> observer) {
                    publishNext(observer, 50, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 50, "one");
                            publishNext(observer, 100, "two");
                            return Subscriptions.empty();
                        }
                    }));

                    publishNext(observer, 130, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishError(observer, 0, new TestException());
                            return Subscriptions.empty();
                        }
                    }));

                    publishNext(observer, 150, Observable.create(new OnSubscribeFunc<String>() {
                        @Override
                        public Subscription onSubscribe(Observer<? super String> observer) {
                            publishNext(observer, 50, "three");
                            return Subscriptions.empty();
                        }
                    }));

                    return Subscriptions.empty();
                }
            });

            Observable<String> sampled = Observable.create(OperationSwitch.switchDo(source));
            sampled.subscribe(observer);

            InOrder inOrder = inOrder(observer);

            scheduler.advanceTimeTo(90, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext(anyString());
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(125, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, times(1)).onNext("one");
            verify(observer, never()).onCompleted();
            verify(observer, never()).onError(any(Throwable.class));

            scheduler.advanceTimeTo(250, TimeUnit.MILLISECONDS);
            inOrder.verify(observer, never()).onNext("three");
            verify(observer, never()).onCompleted();
            verify(observer, times(1)).onError(any(TestException.class));
        }

        private <T> void publishCompleted(final Observer<T> observer, long delay) {
            scheduler.schedule(new Action0() {
                @Override
                public void call() {
                    observer.onCompleted();
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        private <T> void publishError(final Observer<T> observer, long delay, final Throwable error) {
            scheduler.schedule(new Action0() {
                @Override
                public void call() {
                    observer.onError(error);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        private <T> void publishNext(final Observer<T> observer, long delay, final T value) {
            scheduler.schedule(new Action0() {
                @Override
                public void call() {
                    observer.onNext(value);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        @SuppressWarnings("serial")
        private class TestException extends Throwable {
        }
    }
}
