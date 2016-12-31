package org.mu.util;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mu.function.CheckedSupplier;
import org.mu.util.Retryer.Delay;

import com.google.common.truth.ThrowableSubject;
import com.google.common.truth.Truth;

@RunWith(JUnit4.class)
public class RetryerTest {

  @Spy private FakeClock clock;
  @Spy private FakeScheduledExecutorService executor;
  @Mock private Action action;
  private Retryer retryer = new Retryer();

  @Before public void setUpMocks() {
    MockitoAnnotations.initMocks(this);
  }

  @After public void noMoreInteractions() {
    Mockito.verifyNoMoreInteractions(action);
  }

  @Test public void actionSucceedsFirstTime() throws Exception {
    when(action.run()).thenReturn("good");
    assertThat(retry(action::run).toCompletableFuture().get()).isEqualTo("good");
    verify(action).run();
  }

  @Test public void errorPropagated() throws Exception {
    Error error = new Error("test");
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    when(action.run()).thenThrow(error);
    assertException(Error.class, () -> retry(action::run)).isSameAs(error);
    assertThat(error.getSuppressed()).isEmpty();
    verify(action).run();
    verify(delay, never()).beforeDelay(Matchers.<Throwable>any());
    verify(delay, never()).afterDelay(Matchers.<Throwable>any());
  }

  @Test public void uncheckedExceptionPropagated() throws Exception {
    RuntimeException error = new RuntimeException("test");
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    when(action.run()).thenThrow(error);
    assertException(RuntimeException.class, () -> retry(action::run)).isSameAs(error);
    assertThat(error.getSuppressed()).isEmpty();
    verify(action).run();
    verify(delay, never()).beforeDelay(Matchers.<Throwable>any());
    verify(delay, never()).afterDelay(Matchers.<Throwable>any());
  }

  @Test public void actionFailedButNoRetry() throws Exception {
    IOException exception = new IOException("bad");
    when(action.run()).thenThrow(exception);
    assertCauseOf(ExecutionException.class, () -> retry(action::run).toCompletableFuture().get())
        .isSameAs(exception);
    assertThat(exception.getSuppressed()).isEmpty();
    verify(action).run();
  }

  @Test public void exceptionFromBeforeDelayPropagated() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    IOException exception = new IOException();
    when(action.run()).thenThrow(exception);
    RuntimeException unexpected = new RuntimeException();
    Mockito.doThrow(unexpected).when(delay).beforeDelay(Matchers.<Throwable>any());
    assertException(RuntimeException.class, () -> retry(action::run))
        .isSameAs(unexpected);
    assertThat(asList(unexpected.getSuppressed())).containsExactly(exception);
    verify(action).run();
    verify(delay).beforeDelay(exception);
    verify(delay, never()).afterDelay(exception);
  }

  @Test public void exceptionFromAfterDelayResultsInExecutionException() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    IOException exception = new IOException();
    when(action.run()).thenThrow(exception);
    RuntimeException unexpected = new RuntimeException();
    Mockito.doThrow(unexpected).when(delay).afterDelay(Matchers.<Throwable>any());
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isTrue();
    assertCauseOf(ExecutionException.class, () -> stage.toCompletableFuture().get())
        .isSameAs(unexpected);
    assertThat(asList(unexpected.getSuppressed())).containsExactly(exception);
    verify(action).run();
    verify(delay).beforeDelay(exception);
    verify(delay).afterDelay(exception);
  }

  @Test public void actionFailedAndScheduledForRetry() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    IOException exception = new IOException();
    when(action.run()).thenThrow(exception);
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofMillis(999));
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    verify(action).run();
    verify(delay).beforeDelay(exception);
    verify(delay, never()).afterDelay(Matchers.<Throwable>any());
  }

  @Test public void actionFailedAndRetriedToSuccess() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    IOException exception = new IOException();
    when(action.run()).thenThrow(exception).thenReturn("fixed");
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
    assertThat(stage.toCompletableFuture().get()).isEqualTo("fixed");
    verify(action, times(2)).run();
    verify(delay).beforeDelay(exception);
    verify(delay).afterDelay(exception);
  }

  @Test public void errorRetried() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(MyError.class, asList(delay));
    MyError error = new MyError("test");
    when(action.run()).thenThrow(error).thenReturn("fixed");
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
    assertThat(stage.toCompletableFuture().get()).isEqualTo("fixed");
    verify(action, times(2)).run();
    verify(delay).beforeDelay(error);
    verify(delay).afterDelay(error);
  }

  @Test public void uncheckedExceptionRetried() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(RuntimeException.class, asList(delay));
    RuntimeException exception = new RuntimeException("test");
    when(action.run()).thenThrow(exception).thenReturn("fixed");
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
    assertThat(stage.toCompletableFuture().get()).isEqualTo("fixed");
    verify(action, times(2)).run();
    verify(delay).beforeDelay(exception);
    verify(delay).afterDelay(exception);
  }

  @Test public void actionFailedAfterRetry() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    IOException firstException = new IOException();
    IOException exception = new IOException("hopeless");
    when(action.run()).thenThrow(firstException).thenThrow(exception);
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isTrue();
    assertCauseOf(ExecutionException.class, () -> stage.toCompletableFuture().get())
        .isSameAs(exception);
    assertThat(asList(exception.getSuppressed())).containsExactly(firstException);
    verify(action, times(2)).run();
    verify(delay).beforeDelay(firstException);
    verify(delay).afterDelay(firstException);
  }

  @Test public void retrialExceedsTime() throws Exception {
    upon(
        IOException.class,
        ofSeconds(3).timed(Collections.nCopies(100, ofSeconds(1)), clock));
    IOException exception1 = new IOException();
    IOException exception = new IOException("hopeless");
    when(action.run()).thenThrow(exception1).thenThrow(exception);
    CompletionStage<String> stage = retry(action::run);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(2));
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));  // exceeds time
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isTrue();
    assertCauseOf(ExecutionException.class, () -> stage.toCompletableFuture().get())
        .isSameAs(exception);
    assertThat(asList(exception.getSuppressed())).containsExactly(exception1);
    verify(action, times(3)).run();  // Retry twice.
  }

  @Test public void retryBlockinglyWithZeroDelayIsOkayWithJdk() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(0));
    upon(IOException.class, asList(delay));
    IOException exception = new IOException();
    when(action.run()).thenThrow(exception).thenReturn("fixed");
    assertThat(retryer.retryBlockingly(action::run)).isEqualTo("fixed");
    verify(action, times(2)).run();
    verify(delay).beforeDelay(exception);
    verify(delay).afterDelay(exception);
  }

  @Test public void retryWithZeroDelayIsOkayWithJdk() throws Exception {
    ScheduledThreadPoolExecutor realExecutor = new ScheduledThreadPoolExecutor(1);
    try {
      Delay<Throwable> delay = Mockito.spy(ofSeconds(0));
      upon(IOException.class, asList(delay));
      IOException exception = new IOException();
      when(action.run()).thenThrow(exception).thenReturn("fixed");
      assertThat(retryer.retry(action::run, realExecutor).toCompletableFuture().get())
          .isEqualTo("fixed");
      verify(action, times(2)).run();
      verify(delay).beforeDelay(exception);
      verify(delay).afterDelay(exception);
    } finally {
      realExecutor.shutdown();
    }
  }

  @Test public void testCustomDelay() throws Exception {
    class IoDelay extends Delay<IOException> {
      IOException before;
      IOException after;
      @Override public Duration duration() {
        return Duration.ofMillis(1);
      }
      @Override public void beforeDelay(IOException exception) {
        before = exception;
      }
      @Override public void afterDelay(IOException exception) {
        after = exception;
      }
    }
    IoDelay delay = new IoDelay();
    upon(IOException.class, asList(delay).stream());  // to make sure the stream overload works.
    IOException exception = new IOException();
    when(action.run()).thenThrow(exception).thenReturn("fixed");
    CompletionStage<String> stage = retry(action::run);
    elapse(Duration.ofMillis(1));
    assertThat(stage.toCompletableFuture().get()).isEqualTo("fixed");
    verify(action, times(2)).run();
    assertThat(delay.before).isSameAs(exception);
    assertThat(delay.after).isSameAs(exception);
  }

  @Test public void asyncExceptionRetriedToSuccess() throws Exception {
    upon(IOException.class, ofSeconds(1).exponentialBackoff(2, 1));
    when(action.runAsync())
        .thenReturn(exceptionally(new IOException()))
        .thenReturn(CompletableFuture.completedFuture("fixed"));
    CompletionStage<String> stage = retryAsync(action::runAsync);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
    assertThat(stage.toCompletableFuture().get()).isEqualTo("fixed");
    verify(action, times(2)).runAsync();
  }

  @Test public void asyncFailedAfterRetry() throws Exception {
    Delay<Throwable> delay = Mockito.spy(ofSeconds(1));
    upon(IOException.class, asList(delay));
    IOException firstException = new IOException();
    IOException exception = new IOException("hopeless");
    when(action.runAsync())
        .thenReturn(exceptionally(firstException))
        .thenReturn(exceptionally(exception));
    CompletionStage<String> stage = retryAsync(action::runAsync);
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    elapse(Duration.ofSeconds(1));
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isTrue();
    assertCauseOf(ExecutionException.class, () -> stage.toCompletableFuture().get())
        .isSameAs(exception);
    verify(action, times(2)).runAsync();
    verify(delay).beforeDelay(firstException);
    verify(delay).afterDelay(firstException);
  }

  @Test public void testImmutable() throws IOException {
    retryer.upon(IOException.class, asList(ofSeconds(1)));  // Should have no effect
    IOException exception = new IOException("bad");
    when(action.run()).thenThrow(exception);
    assertCauseOf(ExecutionException.class, () -> retry(action::run).toCompletableFuture().get())
        .isSameAs(exception);
    verify(action).run();
  }

  @Test public void guardedList() {
    AtomicBoolean guard = new AtomicBoolean(true);
    List<Integer> list = Delay.guarded(asList(1, 2), guard::get);
    assertThat(list).hasSize(2);
    assertThat(list).isNotEmpty();
    assertThat(list).containsExactly(1, 2);
    guard.set(false);
    assertThat(list).isEmpty();
    guard.set(true);
    assertThat(list).containsExactly(1, 2);
  }

  @Test public void testNulls() {
    assertThrows(NullPointerException.class, () -> new Retryer().retry(null, executor));
    assertThrows(NullPointerException.class, () -> new Retryer().retry(action::run, null));
    assertThrows(NullPointerException.class, () -> new Retryer().retryBlockingly(null));
    assertThrows(NullPointerException.class, () -> new Retryer().retryAsync(null, executor));
    assertThrows(
        NullPointerException.class, () -> new Retryer().retryAsync(action::runAsync, null));
    assertThrows(NullPointerException.class, () -> Delay.guarded(null, () -> true));
    assertThrows(NullPointerException.class, () -> Delay.guarded(asList(), null));
    assertThrows(NullPointerException.class, () -> ofDays(1).timed(null));
    assertThrows(
        NullPointerException.class, () -> ofDays(1).timed(asList(), null));
    assertThrows(NullPointerException.class, () -> Delay.of(null));
  }

  @Test public void testDelay_multiplied() {
    assertThat(ofDays(1).multipliedBy(0)).isEqualTo(ofDays(0));
    assertThat(ofDays(2).multipliedBy(1)).isEqualTo(ofDays(2));
    assertThat(ofDays(3).multipliedBy(2)).isEqualTo(ofDays(6));
    assertThrows(IllegalArgumentException.class, () -> ofDays(1).multipliedBy(-1));
    assertThat(ofDays(1).multipliedBy(Double.MIN_VALUE)).isEqualTo(Delay.ofMillis(1));
  }

  @Test public void testDelay_exponentialBackoff() {
    assertThat(ofDays(1).exponentialBackoff(2, 3))
        .containsExactly(ofDays(1), ofDays(2), ofDays(4))
        .inOrder();
    assertThat(ofDays(1).exponentialBackoff(1, 2))
        .containsExactly(ofDays(1), ofDays(1))
        .inOrder();
    assertThat(ofDays(1).exponentialBackoff(1, 0)).isEmpty();
    assertThrows(IllegalArgumentException.class, () -> ofDays(1).exponentialBackoff(0, 1));
    assertThrows(IllegalArgumentException.class, () -> ofDays(1).exponentialBackoff(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> ofDays(1).exponentialBackoff(2, -1));
  }

  @Test public void testDelay_randomized_invalid() {
    assertThrows(NullPointerException.class, () -> ofDays(1).randomized(null, 1));
    assertThrows(IllegalArgumentException.class, () -> ofDays(1).randomized(new Random(), -0.1));
    assertThrows(IllegalArgumentException.class, () -> ofDays(1).randomized(new Random(), 1.1));
  }

  @Test public void testDelay_randomized_zeroRandomness() {
    Delay<?> delay = ofDays(1).randomized(new Random(), 0);
    assertThat(delay).isEqualTo(ofDays(1));
  }

  @Test public void testDelay_randomized_halfRandomness() {
    Random random = Mockito.mock(Random.class);
    when(random.nextDouble()).thenReturn(0D).thenReturn(0.5D).thenReturn(1D);
    assertThat(ofDays(1).randomized(random, 0.5).duration()).isEqualTo(Duration.ofHours(12));
    assertThat(ofDays(1).randomized(random, 0.5).duration()).isEqualTo(Duration.ofHours(24));
    assertThat(ofDays(1).randomized(random, 0.5).duration()).isEqualTo(Duration.ofHours(36));
  }

  @Test public void testDelay_randomized_fullRandomness() {
    Random random = Mockito.mock(Random.class);
    when(random.nextDouble()).thenReturn(0D).thenReturn(0.5D).thenReturn(1D);
    assertThat(ofDays(1).randomized(random, 1).duration()).isEqualTo(Duration.ofHours(0));
    assertThat(ofDays(1).randomized(random, 1).duration()).isEqualTo(Duration.ofHours(24));
    assertThat(ofDays(1).randomized(random, 1).duration()).isEqualTo(Duration.ofHours(48));
  }

  @Test public void testDelay_equals() {
    Delay<?> one = Delay.ofMillis(1);
    assertThat(one).isEqualTo(one);
    assertThat(one).isEqualTo(Delay.ofMillis(1));
    assertThat(one).isNotEqualTo(Delay.ofMillis(2));
    assertThat(one).isNotEqualTo(Duration.ofMillis(1));
    assertThat(one).isNotEqualTo(null);
    assertThat(one.hashCode()).isEqualTo(Delay.ofMillis(1).hashCode());
  }

  @Test public void testDelay_compareTo() {
    assertThat(Delay.ofMillis(1)).isLessThan(Delay.ofMillis(2));
    assertThat(Delay.ofMillis(1)).isGreaterThan(Delay.ofMillis(0));
    assertThat(Delay.ofMillis(1)).isEquivalentAccordingToCompareTo(Delay.ofMillis(1));
  }

  @Test public void testDelay_of() {
    assertThat(Delay.ofMillis(Long.MAX_VALUE).duration())
        .isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
    assertThat(Delay.ofMillis(0).duration()).isEqualTo(Duration.ofMillis(0));
    assertThat(Delay.ofMillis(1).duration()).isEqualTo(Duration.ofMillis(1));
    assertThat(ofDays(0).duration()).isEqualTo(Duration.ofDays(0));
    assertThat(ofDays(1).duration()).isEqualTo(Duration.ofDays(1));
  }

  @Test public void testDelay_invalid() {
    assertThrows(ArithmeticException.class, () -> ofDays(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Delay.ofMillis(-1));
    assertThrows(ArithmeticException.class, () -> Delay.ofMillis(Long.MIN_VALUE));
    assertThrows(IllegalArgumentException.class, () -> ofDays(-1));
  }

  @Test public void testFakeScheduledExecutorService_taskScheduledButNotRunYet() {
    Runnable runnable = mock(Runnable.class);
    executor.schedule(runnable, 2, TimeUnit.MILLISECONDS);
    elapse(Duration.ofMillis(1));
    Mockito.verifyZeroInteractions(runnable);
  }

  @Test public void testFakeScheduledExecutorService_taskScheduledAndRun() {
    Runnable runnable = mock(Runnable.class);
    executor.schedule(runnable, 2, TimeUnit.MILLISECONDS);
    elapse(Duration.ofMillis(2));
    verify(runnable).run();
    elapse(Duration.ofMillis(2));
    Mockito.verifyNoMoreInteractions(runnable);
  }

  private static CompletionStage<String> exceptionally(Throwable e) {
    CompletableFuture<String> future = new CompletableFuture<>();
    future.completeExceptionally(e);
    return future;
  }

  private static <E extends Throwable> Delay<E> ofSeconds(long seconds) {
    return Delay.of(Duration.ofSeconds(seconds));
  }

  private static <E extends Throwable> Delay<E> ofDays(long days) {
    return Delay.of(Duration.ofDays(days));
  }

  private <E extends Throwable> void upon(
      Class<E> exceptionType, List<? extends Delay<? super E>> delays) {
    retryer = retryer.upon(exceptionType, delays);
  }

  private <E extends Throwable> void upon(
      Class<E> exceptionType, Stream<? extends Delay<? super E>> delays) {
    retryer = retryer.upon(exceptionType, delays);
  }

  private <T> CompletionStage<T> retry(CheckedSupplier<T, ?> supplier) {
    return retryer.retry(supplier, executor);
  }

  private <T> CompletionStage<T> retryAsync(
      CheckedSupplier<? extends CompletionStage<T>, ?> supplier) {
    return retryer.retryAsync(supplier, executor);
  }

  private ThrowableSubject assertException(
      Class<? extends Throwable> exceptionType, Executable executable) {
    return Truth.assertThat(Assertions.assertThrows(exceptionType, executable));
  }

  private ThrowableSubject assertCauseOf(
      Class<? extends Throwable> exceptionType, Executable executable) {
    return assertThat(Assertions.assertThrows(exceptionType, executable).getCause());
  }

  private void elapse(Duration duration) {
    clock.elapse(duration);
    executor.tick();
  }

  abstract static class FakeClock extends Clock {
    private Instant now = Instant.ofEpochMilli(123456789L);

    @Override public Instant instant() {
      return now;
    }

    void elapse(Duration duration) {
      now = now.plus(duration);
    }
  }

  abstract class FakeScheduledExecutorService implements ScheduledExecutorService {

    private List<Schedule> schedules = new ArrayList<>();

    void tick() {
      Instant now = clock.instant();
      
      schedules.stream()
          .filter(s -> s.ready(now))
          // The commands can call schedule() to schedule another retry.
          // So if we don't make a copy, we get a ConcurrentModificationException.
          .collect(Collectors.toList())
          .forEach(s -> s.command.run());
      schedules = schedules.stream()
          .filter(s -> s.pending(now))
          .collect(Collectors.toCollection(ArrayList::new));
    }
  
    @Override public void execute(Runnable command) {
      schedule(command, 1, TimeUnit.MILLISECONDS);
    }

    @Override public ScheduledFuture<?> schedule(
        Runnable command, long delay, TimeUnit unit) {
      assertThat(unit).isEqualTo(TimeUnit.MILLISECONDS);
      schedules.add(new Schedule(clock.instant().plus(delay, ChronoUnit.MILLIS), command));
      return null;  // Retryer doesn't use the return.
    }
  }

  private static final class Schedule {
    private final Instant time;
    final Runnable command;

    Schedule(Instant time, Runnable command) {
      this.time = requireNonNull(time);
      this.command = requireNonNull(command);
    }

    boolean ready(Instant now) {
      return !pending(now);
    }

    boolean pending(Instant now) {
      return now.isBefore(time);
    }
  }

  private interface Action {
    String run() throws IOException;
    CompletionStage<String> runAsync() throws IOException;
  }

  @SuppressWarnings("serial")
  private static final class MyError extends Error {
    MyError(String message) {
      super(message);
    }
  }
}
