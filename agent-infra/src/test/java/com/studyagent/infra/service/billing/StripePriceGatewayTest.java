package com.studyagent.infra.service.billing;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Price;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StripePriceGatewayTest {
    private static final String PRICE_ID = "price_basic";
    private final AtomicLong now = new AtomicLong();
    private final StripePriceGateway gateway = spy(new StripePriceGateway(now::get));

    @Test
    void coldReadCachesForTenMinutes() throws Exception {
        Price initial = new Price();
        Price updated = new Price();
        doReturn(initial, updated).when(gateway).fetchPrice(PRICE_ID, false);

        assertSame(initial, gateway.retrieve(PRICE_ID, false));
        now.set(Duration.ofMinutes(10).minusSeconds(1).toNanos());
        assertSame(initial, gateway.retrieve(PRICE_ID, false));
        verify(gateway).fetchPrice(PRICE_ID, false);

        now.set(Duration.ofMinutes(10).toNanos());
        assertSame(updated, gateway.retrieve(PRICE_ID, false));
        verify(gateway, times(2)).fetchPrice(PRICE_ID, false);
    }

    @Test
    void prewarmAndRefreshKeepDirectoryReadsOffStripe() throws Exception {
        Price initial = new Price();
        Price updated = new Price();
        doReturn(initial, updated).when(gateway).fetchPrice(PRICE_ID, true);

        gateway.refreshIfDue(PRICE_ID);
        assertSame(initial, gateway.retrieve(PRICE_ID, false));
        now.set(Duration.ofMinutes(8).minusSeconds(1).toNanos());
        gateway.refreshIfDue(PRICE_ID);
        verify(gateway).fetchPrice(PRICE_ID, true);

        now.set(Duration.ofMinutes(8).toNanos());
        gateway.refreshIfDue(PRICE_ID);
        now.set(Duration.ofMinutes(10).toNanos());
        assertSame(updated, gateway.retrieve(PRICE_ID, false));
        verify(gateway, times(2)).fetchPrice(PRICE_ID, true);
        verify(gateway, never()).fetchPrice(PRICE_ID, false);
    }

    @Test
    void refreshFailurePreservesPriceUntilExpiryAndConfirmationAlwaysFetches() throws Exception {
        Price initial = new Price();
        Price confirmed = new Price();
        ApiConnectionException unavailable = new ApiConnectionException("Stripe unavailable");
        doReturn(initial).doThrow(unavailable).when(gateway).fetchPrice(PRICE_ID, true);
        doThrow(unavailable).doReturn(confirmed).when(gateway).fetchPrice(PRICE_ID, false);

        gateway.refreshIfDue(PRICE_ID);
        now.set(Duration.ofMinutes(8).toNanos());
        assertThrows(ApiConnectionException.class, () -> gateway.refreshIfDue(PRICE_ID));
        assertSame(initial, gateway.retrieve(PRICE_ID, false));
        assertThrows(ApiConnectionException.class, () -> gateway.retrieve(PRICE_ID, true));
        assertSame(initial, gateway.retrieve(PRICE_ID, false));
        assertSame(confirmed, gateway.retrieve(PRICE_ID, true));
        assertSame(confirmed, gateway.retrieve(PRICE_ID, false));
    }

    @Test
    void failedRefreshDoesNotExtendExpiry() throws Exception {
        Price recovered = new Price();
        ApiConnectionException unavailable = new ApiConnectionException("Stripe unavailable");
        doReturn(new Price()).doThrow(unavailable).when(gateway).fetchPrice(PRICE_ID, true);
        doReturn(recovered).when(gateway).fetchPrice(PRICE_ID, false);

        gateway.refreshIfDue(PRICE_ID);
        now.set(Duration.ofMinutes(8).toNanos());
        assertThrows(ApiConnectionException.class, () -> gateway.refreshIfDue(PRICE_ID));
        now.set(Duration.ofMinutes(10).toNanos());
        assertSame(recovered, gateway.retrieve(PRICE_ID, false));
        verify(gateway).fetchPrice(PRICE_ID, false);
    }

    @Test
    void validPriceRemainsReadableDuringBackgroundRefresh() throws Exception {
        Price initial = new Price();
        Price updated = new Price();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);
        doReturn(initial).doAnswer(call -> {
            refreshStarted.countDown();
            assertTrue(finishRefresh.await(3, TimeUnit.SECONDS));
            return updated;
        }).when(gateway).fetchPrice(PRICE_ID, true);
        gateway.refreshIfDue(PRICE_ID);
        now.set(Duration.ofMinutes(8).toNanos());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var refresh = executor.submit(() -> {
                gateway.refreshIfDue(PRICE_ID);
                return null;
            });
            assertTrue(refreshStarted.await(3, TimeUnit.SECONDS));
            assertSame(initial, executor.submit(() -> gateway.retrieve(PRICE_ID, false)).get(2, TimeUnit.SECONDS));
            finishRefresh.countDown();
            refresh.get(3, TimeUnit.SECONDS);
            assertSame(updated, gateway.retrieve(PRICE_ID, false));
            verify(gateway, never()).fetchPrice(PRICE_ID, false);
        } finally {
            finishRefresh.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void coldRequestSharesAnInFlightPrewarmForTheSamePrice() throws Exception {
        Price price = new Price();
        CountDownLatch prewarmStarted = new CountDownLatch(1);
        CountDownLatch finishPrewarm = new CountDownLatch(1);
        doAnswer(call -> {
            prewarmStarted.countDown();
            assertTrue(finishPrewarm.await(3, TimeUnit.SECONDS));
            return price;
        }).when(gateway).fetchPrice(PRICE_ID, true);
        doReturn(price).when(gateway).fetchPrice(PRICE_ID, false);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var prewarm = executor.submit(() -> {
                gateway.refreshIfDue(PRICE_ID);
                return null;
            });
            assertTrue(prewarmStarted.await(3, TimeUnit.SECONDS));
            var request = executor.submit(() -> gateway.retrieve(PRICE_ID, false));
            assertThrows(TimeoutException.class, () -> request.get(100, TimeUnit.MILLISECONDS));
            finishPrewarm.countDown();
            assertSame(price, request.get(3, TimeUnit.SECONDS));
            prewarm.get(3, TimeUnit.SECONDS);
            verify(gateway, never()).fetchPrice(PRICE_ID, false);
        } finally {
            finishPrewarm.countDown();
            executor.shutdownNow();
        }
    }
}
