package rsc.publisher;

import org.junit.Test;
import rsc.processor.DirectProcessor;
import rsc.test.TestSubscriber;

public class PublisherLatestTest {
    @Test(expected = NullPointerException.class)
    public void sourceNull() {
        new PublisherLatest<>(null);
    }

    @Test
    public void normal() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();

        new PublisherLatest<>(new PublisherRange(1, 10)).subscribe(ts);

        ts.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
          .assertNoError()
          .assertComplete();
    }

    @Test
    public void backpressured() {
        DirectProcessor<Integer> tp = new DirectProcessor<>();

        TestSubscriber<Integer> ts = new TestSubscriber<>(0);

        new PublisherLatest<>(tp).subscribe(ts);

        tp.onNext(1);

        ts.assertNoValues().assertNoError().assertNotComplete();

        tp.onNext(2);

        ts.request(1);

        ts.assertValue(2).assertNoError().assertNotComplete();

        tp.onNext(3);
        tp.onNext(4);

        ts.request(2);

        ts.assertValues(2, 4).assertNoError().assertNotComplete();

        tp.onNext(5);
        tp.onComplete();

        ts.assertValues(2, 4, 5).assertNoError().assertComplete();
    }

    @Test
    public void error() {
        DirectProcessor<Integer> tp = new DirectProcessor<>();

        TestSubscriber<Integer> ts = new TestSubscriber<>(0);

        new PublisherLatest<>(tp).subscribe(ts);

        tp.onError(new RuntimeException("forced failure"));

        ts.assertNoValues()
          .assertNotComplete()
          .assertError(RuntimeException.class)
          .assertErrorMessage("forced failure");
    }
    
    @Test
    public void backpressureWithDrop() {
        DirectProcessor<Integer> tp = new DirectProcessor<>();

        TestSubscriber<Integer> ts = new TestSubscriber<Integer>(0) {
            @Override
            public void onNext(Integer t) {
                super.onNext(t);
                if (t == 2) {
                    tp.onNext(3);
                }
            }
        };

        tp.onBackpressureLatest().subscribe(ts);
        
        tp.onNext(1);
        tp.onNext(2);
        
        ts.request(1);
        
        ts.assertValue(2)
        .assertNoError()
        .assertNotComplete();
        
    }
}
