package org.mu.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;

import com.google.common.truth.Subject;
import com.google.common.truth.ThrowableSubject;

/** Some common assertions for {@link CompletableFuture}, while Truth doesn't have them yet. */
final class FutureAssertions {

  static ThrowableSubject assertCauseOf(
      Class<? extends Throwable> exceptionType, CompletionStage<?> stage) {
    CompletableFuture<?> future = stage.toCompletableFuture();
    Throwable thrown = Assertions.assertThrows(exceptionType, future::get);
    assertThat(future.isDone()).isTrue();
    assertThat(future.isCompletedExceptionally()).isTrue();
    return assertThat(thrown.getCause());
  }

  static CancellationException assertCancelled(CompletionStage<?> stage) {
    CompletableFuture<?> future = stage.toCompletableFuture();
    assertThat(future.isDone()).isTrue();
    assertThat(future.isCompletedExceptionally()).isTrue();
    assertThat(future.isCancelled()).isTrue();
    return assertThrows(CancellationException.class, future::get);
  }

  static Subject<?, Object> assertCompleted(CompletionStage<?> stage)
      throws InterruptedException, ExecutionException {
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    Object result = stage.toCompletableFuture().get();
    assertThat(stage.toCompletableFuture().isCancelled()).isFalse();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
    return assertThat(result);
  }

  static Subject<?, Object> assertAfterCompleted(CompletionStage<?> stage)
      throws InterruptedException, ExecutionException {
    Object result = stage.toCompletableFuture().get();
    assertThat(stage.toCompletableFuture().isDone()).isTrue();
    assertThat(stage.toCompletableFuture().isCancelled()).isFalse();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
    return assertThat(result);
  }

  static void assertPending(CompletionStage<?> stage) {
    assertThat(stage.toCompletableFuture().isDone()).isFalse();
    assertThat(stage.toCompletableFuture().isCancelled()).isFalse();
    assertThat(stage.toCompletableFuture().isCompletedExceptionally()).isFalse();
  }
}
