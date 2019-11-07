package io.zeebe.broker.clustering.atomix;

import io.atomix.protocols.raft.RaftException;
import io.atomix.protocols.raft.RaftStateMachine;
import io.atomix.protocols.raft.impl.RaftContext;
import io.atomix.protocols.raft.storage.log.RaftLogReader;
import io.atomix.protocols.raft.storage.log.entry.RaftLogEntry;
import io.atomix.storage.journal.Indexed;
import io.atomix.utils.concurrent.ThreadContext;
import io.atomix.utils.concurrent.ThreadContextFactory;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZeebeRaftStateMachine implements RaftStateMachine {
  private final RaftContext raft;
  private final ThreadContext threadContext;
  private final ThreadContextFactory threadContextFactory;

  // hard coupled state
  private final RaftLogReader reader;
  private final Logger logger;
  private final ThreadContext compactionContext;

  // used when performing compaction; may be updated from a different thread
  private volatile long compactableIndex;

  // represents the last enqueued index
  private long lastEnqueued;

  public ZeebeRaftStateMachine(
      final RaftContext raft,
      final ThreadContext threadContext,
      final ThreadContextFactory threadContextFactory) {
    this.raft = raft;
    this.threadContext = threadContext;
    this.threadContextFactory = threadContextFactory;

    this.compactionContext = this.threadContextFactory.createContext();
    this.lastEnqueued = this.raft.getSnapshotStore().getCurrentSnapshotIndex();
    this.reader = raft.getLog().openReader(this.lastEnqueued, RaftLogReader.Mode.COMMITS);
    this.logger = LoggerFactory.getLogger(this.getClass());
  }

  @Override
  public ThreadContext executor() {
    return threadContext;
  }

  /**
   * Assumes our snapshots are being taken asynchronously and we regularly update the compactable
   * index. Compaction is performed asynchronously.
   *
   * @return a future which is completed when the log has been compacted
   */
  @Override
  public CompletableFuture<Void> compact() {
    final var log = raft.getLog();
    if (log.isCompactable(compactableIndex)) {
      final var index = log.getCompactableIndex(compactableIndex);
      if (index > reader.getFirstIndex()) {
        final var future = new CompletableFuture<Void>();
        logger.debug("Compacting log up from {} up to {}", reader.getFirstIndex(), index);
        compactionContext.execute(() -> safeCompact(index, future));
        return future;
      }
    }

    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void applyAll(final long index) {
    threadContext.execute(() -> safeApplyAll(index));
  }

  @Override
  public <T> CompletableFuture<T> apply(final long index) {
    final var future = new CompletableFuture<T>();
    threadContext.execute(() -> safeApplyIndex(index, future));
    return future;
  }

  @Override
  public <T> CompletableFuture<T> apply(final Indexed<? extends RaftLogEntry> entry) {
    final CompletableFuture<T> future = new CompletableFuture<>();
    threadContext.execute(() -> safeApplyIndexed(entry, future));
    return future;
  }

  @Override
  public void close() {
    reader.close();
  }

  @Override
  public void setCompactablePosition(final long index, final long term) {
    this.compactableIndex = index;
  }

  @Override
  public long getCompactableIndex() {
    return compactableIndex;
  }

  @Override
  public long getCompactableTerm() {
    throw new UnsupportedOperationException(
        "getCompactableTerm is not required by this implementation");
  }

  private void safeCompact(final long index, final CompletableFuture<Void> future) {
    compactionContext.checkThread();

    try {
      raft.getLog().compact(index);
      future.complete(null);
    } catch (final Exception e) {
      logger.error("Failed to compact up to index {}", index, e);
      future.completeExceptionally(e);
    }
  }

  private void safeApplyAll(final long index) {
    threadContext.checkThread();

    final long currentSnapshotIndex = raft.getSnapshotStore().getCurrentSnapshotIndex();
    lastEnqueued = Math.max(currentSnapshotIndex, lastEnqueued);
    while (lastEnqueued < index) {
      final long nextIndex = ++lastEnqueued;
      threadContext.execute(() -> safeApplyIndex(nextIndex, null));
    }
  }

  private <T> void safeApplyIndex(final long index, final CompletableFuture<T> future) {
    threadContext.checkThread();

    // Apply entries prior to this entry.
    if (reader.hasNext() && reader.getNextIndex() == index) {
      try {
        safeApplyIndexed(reader.next(), future);
      } catch (final Exception e) {
        logger.error("Failed to apply entry at index {}", index, e);
        if (future != null) {
          future.completeExceptionally(e);
        }
      }
    } else {
      logger.error("Cannot apply index {}", index);
      if (future != null) {
        future.completeExceptionally(new IndexOutOfBoundsException("Cannot apply index " + index));
      }
    }
  }

  private <T> void safeApplyIndexed(
      final Indexed<? extends RaftLogEntry> indexed, final CompletableFuture<T> future) {
    threadContext.checkThread();
    logger.trace("Applying {}", indexed);

    // skip if we have a newer snapshot
    // if we skip, then we'll probably want to notify once we reach the snapshot
    if (raft.getSnapshotStore().getCurrentSnapshotIndex() > indexed.index()) {
      future.complete(null);
      return;
    }

    if (RaftLogEntry.class.isAssignableFrom(indexed.type())) {
      raft.notifyCommitListeners(indexed);
      if (future != null) {
        future.complete(null);
      }
    } else if (future != null) {
      future.completeExceptionally(new RaftException.ProtocolException("Unknown indexed type"));
    }

    // mark as applied regardless of result
    raft.setLastApplied(indexed.index(), indexed.entry().term());
  }
}