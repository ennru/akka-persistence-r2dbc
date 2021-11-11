/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.r2dbc.internal

import java.time.Clock
import java.time.Instant
import java.time.{ Duration => JDuration }
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.persistence.query.DurableStateChange
import akka.persistence.query.UpdatedDurableState
import akka.persistence.query.scaladsl.EventTimestampQuery
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.internal.SliceUtils
import akka.persistence.r2dbc.query.TimestampOffset
import akka.projection.MergeableOffset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.TimestampOffsetBySlicesSourceProvider
import akka.projection.internal.ManagementState
import akka.projection.internal.OffsetSerialization
import akka.projection.internal.OffsetSerialization.MultipleOffsets
import akka.projection.internal.OffsetSerialization.SingleOffset
import akka.projection.r2dbc.R2dbcProjectionSettings
import io.r2dbc.spi.Connection
import io.r2dbc.spi.Statement
import org.slf4j.LoggerFactory

object R2dbcOffsetStore {
  type SeqNr = Long
  type Pid = String

  val MaxNumberOfSlices = 128 // FIXME define this in akka.persistence.Persistence (not per plugin)

  final case class Record(pid: Pid, seqNr: SeqNr, timestamp: Instant)
  final case class RecordWithOffset(record: Record, offset: TimestampOffset, strictSeqNr: Boolean)

  object State {
    val empty: State = State(Map.empty, Vector.empty, Instant.EPOCH)

    def apply(records: immutable.IndexedSeq[Record]): State = {
      if (records.isEmpty) empty
      else empty.add(records)
    }
  }

  // FIXME add unit test for this class
  final case class State(byPid: Map[Pid, Record], latest: immutable.IndexedSeq[Record], oldestTimestamp: Instant) {
    def size: Int = byPid.size

    def latestTimestamp: Instant =
      if (latest.isEmpty) Instant.EPOCH
      else latest.head.timestamp

    def latestOffset: Option[TimestampOffset] = {
      if (latest.isEmpty)
        None
      else
        Some(TimestampOffset(latestTimestamp, latest.map(r => r.pid -> r.seqNr).toMap))
    }

    def add(records: immutable.IndexedSeq[Record]): State = {
      records.foldLeft(this) { case (acc, r) =>
        val newByPid =
          acc.byPid.get(r.pid) match {
            case Some(existingRecord) =>
              if (r.seqNr > existingRecord.seqNr)
                acc.byPid.updated(r.pid, r)
              else
                acc.byPid // older or same seqNr (not expected, but handled)
            case None =>
              acc.byPid.updated(r.pid, r)
          }

        val latestTimestamp = acc.latestTimestamp
        val newLatest =
          if (r.timestamp.isAfter(latestTimestamp))
            Vector(r)
          else if (r.timestamp == latestTimestamp)
            acc.latest :+ r
          else
            acc.latest // older than existing latest, keep existing latest
        val newOldestTimestamp =
          if (acc.oldestTimestamp == Instant.EPOCH)
            r.timestamp // first record
          else if (r.timestamp.isBefore(acc.oldestTimestamp))
            r.timestamp // not expected, but handled
          else
            acc.oldestTimestamp // this is the normal case

        acc.copy(byPid = newByPid, latest = newLatest, oldestTimestamp = newOldestTimestamp)
      }
    }

    def isDuplicate(record: Record): Boolean = {
      byPid.get(record.pid) match {
        case Some(existingRecord) => record.seqNr <= existingRecord.seqNr
        case None                 => false
      }
    }

    def window: JDuration =
      JDuration.between(oldestTimestamp, latestTimestamp)

    def evict(until: Instant): State = {
      if (oldestTimestamp.isBefore(until))
        State(byPid.valuesIterator.filterNot(_.timestamp.isBefore(until)).toVector)
      else
        this
    }
  }

  val FutureDone: Future[Done] = Future.successful(Done)
  val FutureTrue: Future[Boolean] = Future.successful(true)
  val FutureFalse: Future[Boolean] = Future.successful(false)
}

/**
 * INTERNAL API
 */
@InternalApi
private[projection] class R2dbcOffsetStore(
    projectionId: ProjectionId,
    sourceProvider: Option[TimestampOffsetBySlicesSourceProvider],
    system: ActorSystem[_],
    settings: R2dbcProjectionSettings,
    r2dbcExecutor: R2dbcExecutor,
    clock: Clock = Clock.systemUTC()) {

  import R2dbcOffsetStore._

  // FIXME include projectionId in all log messages
  private val logger = LoggerFactory.getLogger(this.getClass)

  private val evictWindow = settings.timeWindow.plus(settings.evictInterval)

  private val offsetSerialization = new OffsetSerialization(system)
  import offsetSerialization.fromStorageRepresentation
  import offsetSerialization.toStorageRepresentation

  private val timestampOffsetTable = settings.timestampOffsetTableWithSchema
  private val offsetTable = settings.offsetTableWithSchema

  // FIXME define this in akka.persistence.Persistence (not per plugin)
  private val maxNumberOfSlices = MaxNumberOfSlices

  private[projection] implicit val executionContext: ExecutionContext = system.executionContext

  private val selectTimestampOffsetSql: String =
    s"SELECT * FROM $timestampOffsetTable WHERE slice BETWEEN $$1 AND $$2 AND projection_name = $$3"

  // FIXME an alternative would be pk: (projection_name, projection_key, timestamp_offset, persistence_id)
  // which might be better for the range deletes, but it would would be "append only" with possibly
  // many rows per persistence_id
  private val upsertTimestampOffsetSql: String =
    s"INSERT INTO $timestampOffsetTable " +
    "(projection_name, projection_key, slice, persistence_id, seq_nr, timestamp_offset, last_updated)  " +
    "VALUES ($1,$2,$3,$4,$5,$6, transaction_timestamp()) " +
    "ON CONFLICT (slice, projection_name, persistence_id) " +
    "DO UPDATE SET " +
    "projection_key = excluded.projection_key, " +
    "seq_nr = excluded.seq_nr, " +
    "timestamp_offset = excluded.timestamp_offset, " +
    "last_updated = excluded.last_updated"

  private val deleteTimestampOffsetSql: String =
    s"DELETE FROM $timestampOffsetTable WHERE slice BETWEEN $$1 AND $$2 AND projection_name = $$3 AND timestamp_offset < $$4"

  private val selectOffsetSql: String =
    s"SELECT * FROM $offsetTable WHERE projection_name = $$1"

  private val upsertOffsetSql: String =
    s"INSERT INTO $offsetTable  " +
    "(projection_name, projection_key, current_offset, manifest, mergeable, last_updated) " +
    "VALUES ($1,$2,$3,$4,$5,$6) " +
    "ON CONFLICT (projection_name, projection_key) " +
    "DO UPDATE SET " +
    "current_offset = excluded.current_offset, " +
    "manifest = excluded.manifest, " +
    "mergeable = excluded.mergeable, " +
    "last_updated = excluded.last_updated"

  private val clearOffsetSql: String =
    s"""DELETE FROM $offsetTable WHERE projection_name = $$1 AND projection_key = $$2"""

  // The OffsetStore instance is used by a single projectionId and there shouldn't be any concurrent
  // calls to methods that access the `state`. To detect any violations of that concurrency assumption
  // we use AtomicReference and fail if the CAS fails.
  private val state = new AtomicReference(State.empty)

  // Transient state of inflight pid -> seqNr (before they have been stored and included in `state`), which is
  // needed for at-least-once or other projections where the offset is saved afterwards. Not needed for exactly-once.
  // This can be updated concurrently with CAS retries.
  private val inflight = new AtomicReference(Map.empty[Pid, SeqNr])

  system.scheduler.scheduleWithFixedDelay(
    settings.deleteInterval,
    settings.deleteInterval,
    () => deleteOldTimestampOffsets(),
    system.executionContext)

  private def timestampOffsetBySlicesSourceProvider: TimestampOffsetBySlicesSourceProvider =
    sourceProvider match {
      case Some(provider) => provider
      case _ =>
        throw new IllegalArgumentException(
          s"Expected TimestampOffsetBySlicesSourceProvider to be defined when TimestampOffset is used.")
    }

  private def eventTimestampQuery: EventTimestampQuery =
    timestampOffsetBySlicesSourceProvider match {
      case timestampQuery: EventTimestampQuery => timestampQuery
      case _ =>
        throw new IllegalArgumentException(
          s"Expected TimestampOffsetBySlicesSourceProvider to implement EventTimestampQuery when TimestampOffset is used.")
    }

  def getState(): State =
    state.get()

  def getInflight(): Map[Pid, SeqNr] =
    inflight.get()

  def getOffset[Offset](): Future[Option[Offset]] = {
    getState().latestOffset match {
      case Some(t) => Future.successful(Some(t.asInstanceOf[Offset]))
      case None    => readOffset()
    }
  }

  def readOffset[Offset](): Future[Option[Offset]] = {
    // look for TimestampOffset first since that is used by akka-persistence-r2dbc,
    // and then fall back to the other more primitive offset types
    readTimestampOffset().flatMap {
      case Some(t) => Future.successful(Some(t.asInstanceOf[Offset]))
      case None    => readPrimitiveOffset()
    }
  }

  private def readTimestampOffset(): Future[Option[TimestampOffset]] = {
    val oldState = state.get()

    val (minSlice, maxSlice) = {
      sourceProvider match {
        case Some(provider) => (provider.minSlice, provider.maxSlice)
        case None           => (0, R2dbcOffsetStore.MaxNumberOfSlices - 1)
      }
    }

    val recordsFut = r2dbcExecutor.select("read timestamp offset")(
      conn => {
        logger.trace("reading timestamp offset for [{}]", projectionId)
        conn
          .createStatement(selectTimestampOffsetSql)
          .bind(0, minSlice)
          .bind(1, maxSlice)
          .bind(2, projectionId.name)
      },
      row => {
        val pid = row.get("persistence_id", classOf[String])
        val seqNr = row.get("seq_nr", classOf[java.lang.Long])
        val timestamp = row.get("timestamp_offset", classOf[Instant])
        Record(pid, seqNr, timestamp)
      })
    recordsFut.map { records =>
      val newState = State(records)
      logger.debug(
        "readTimestampOffset state with [{}] persistenceIds, oldest [{}], latest [{}]",
        newState.byPid.size,
        newState.oldestTimestamp,
        newState.latestTimestamp)
      if (!state.compareAndSet(oldState, newState))
        throw new IllegalStateException("Unexpected concurrent modification of state from readOffset.")
      clearInflight()
      if (newState == State.empty) {
        None
      } else {
        newState.latestOffset
      }
    }
  }

  private def readPrimitiveOffset[Offset](): Future[Option[Offset]] = {
    val singleOffsets = r2dbcExecutor.select("read offset")(
      conn => {
        logger.trace("reading offset for [{}]", projectionId)
        conn
          .createStatement(selectOffsetSql)
          .bind(0, projectionId.name)
      },
      row => {
        val offsetStr = row.get("current_offset", classOf[String])
        val manifest = row.get("manifest", classOf[String])
        val mergeable = row.get("mergeable", classOf[java.lang.Boolean])
        val key = row.get("projection_key", classOf[String])

        val adaptedProjectionId = ProjectionId(projectionId.name, key)
        SingleOffset(adaptedProjectionId, manifest, offsetStr, mergeable)
      })

    singleOffsets.map { offsets =>
      val result =
        if (offsets.isEmpty) None
        else if (offsets.forall(_.mergeable)) {
          Some(
            fromStorageRepresentation[MergeableOffset[_], Offset](MultipleOffsets(offsets.toList))
              .asInstanceOf[Offset])
        } else {
          offsets.find(_.id == projectionId).map(fromStorageRepresentation[Offset, Offset])
        }

      logger.trace2("found offset [{}] for [{}]", result, projectionId)

      result
    }
  }

  /**
   * Like saveOffsetInTx, but in own transaction. Used by atLeastOnce and for resetting an offset.
   */
  def saveOffset[Offset](offset: Offset): Future[Done] = {
    r2dbcExecutor
      .withConnection("save offset") { conn =>
        saveOffsetInTx(conn, offset)
      }
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  /**
   * This method is used together with the users' handler code and run in same transaction.
   */
  def saveOffsetInTx[Offset](conn: Connection, offset: Offset): Future[Done] = {
    offset match {
      case t: TimestampOffset =>
        // TODO possible perf improvement to optimize for the normal case of 1 record
        val records = t.seen.map { case (pid, seqNr) => Record(pid, seqNr, t.timestamp) }.toVector
        saveTimestampOffsetInTx(conn, records)
      case _ =>
        savePrimitiveOffsetInTx(conn, offset)
    }
  }

  def saveOffsets[Offset](offsets: immutable.IndexedSeq[Offset]): Future[Done] = {
    r2dbcExecutor
      .withConnection("save offsets") { conn =>
        saveOffsetsInTx(conn, offsets)
      }
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  def saveOffsetsInTx[Offset](conn: Connection, offsets: immutable.IndexedSeq[Offset]): Future[Done] = {
    if (offsets.exists(_.isInstanceOf[TimestampOffset])) {
      val records = offsets.flatMap {
        case t: TimestampOffset =>
          t.seen.map { case (pid, seqNr) => Record(pid, seqNr, t.timestamp) }
        case _ =>
          Nil
      }
      saveTimestampOffsetInTx(conn, records)
    } else {
      savePrimitiveOffsetInTx(conn, offsets.last)
    }
  }

  private def saveTimestampOffsetInTx[Offset](conn: Connection, records: immutable.IndexedSeq[Record]): Future[Done] = {
    val oldState = state.get()
    val filteredRecords = records.filterNot(oldState.isDuplicate)
    if (filteredRecords.isEmpty) {
      FutureDone
    } else {
      // FIXME change to trace
      logger.debug("saving timestamp offset [{}], {}", filteredRecords.last.timestamp, filteredRecords)

      def bindRecord(stmt: Statement, record: Record): Statement = {
        val slice = SliceUtils.sliceForPersistenceId(record.pid, maxNumberOfSlices)
        val minSlice = timestampOffsetBySlicesSourceProvider.minSlice
        val maxSlice = timestampOffsetBySlicesSourceProvider.maxSlice
        if (slice < minSlice || slice > maxSlice)
          throw new IllegalArgumentException(
            s"This offset store [$projectionId] manages slices " +
            s"[$minSlice - $maxSlice] but received slice [$slice] for persistenceId [${record.pid}]")

        stmt
          .bind(0, projectionId.name)
          .bind(1, projectionId.key)
          .bind(2, slice)
          .bind(3, record.pid)
          .bind(4, record.seqNr)
          .bind(5, record.timestamp)
      }

      val newState = oldState.add(filteredRecords)

      // accumulate some more than the timeWindow before evicting
      val evictedNewState =
        if (newState.window.compareTo(evictWindow) > 0) {
          val s = newState.evict(newState.latestTimestamp.minus(settings.timeWindow))
          logger.debug("Evicted [{}] records, keeping [{}] records.", newState.size - s.size, s.size)
          s
        } else
          newState

      val statement = conn.createStatement(upsertTimestampOffsetSql)

      val offsetUpserts =
        if (filteredRecords.size == 1) {
          val boundStatement = bindRecord(statement, filteredRecords.head)
          R2dbcExecutor.updateOneInTx(boundStatement)
        } else {
          val boundStatement =
            filteredRecords.foldLeft(statement) { (stmt, rec) =>
              bindRecord(stmt, rec).add()
            }
          R2dbcExecutor.updateBatchInTx(boundStatement)
        }

      offsetUpserts.map { _ =>
        if (state.compareAndSet(oldState, evictedNewState))
          cleanupInflight(evictedNewState)
        else
          throw new IllegalStateException("Unexpected concurrent modification of state from saveOffset.")
        Done
      }
    }
  }

  @tailrec private def cleanupInflight(newState: State): Unit = {
    val currentInflight = getInflight()
    val newInflight =
      currentInflight.filter {
        case (inflightPid, inflightSeqNr) =>
          newState.byPid.get(inflightPid) match {
            case Some(r) => r.seqNr < inflightSeqNr
            case None    => true
          }
        case _ => true
      }
    if (!inflight.compareAndSet(currentInflight, newInflight))
      cleanupInflight(newState) // CAS retry, concurrent update of inflight
  }

  @tailrec private def clearInflight(): Unit = {
    val currentInflight = getInflight()
    if (!inflight.compareAndSet(currentInflight, Map.empty[Pid, SeqNr]))
      clearInflight() // CAS retry, concurrent update of inflight
  }

  private def savePrimitiveOffsetInTx[Offset](conn: Connection, offset: Offset): Future[Done] = {
    logger.trace("saving offset [{}]", offset)

    val now = Instant.now(clock).toEpochMilli

    // FIXME can we move serialization outside the transaction?
    val storageReps = toStorageRepresentation(projectionId, offset)

    def upsertStmt(singleOffset: SingleOffset): Statement = {
      conn
        .createStatement(upsertOffsetSql)
        .bind(0, singleOffset.id.name)
        .bind(1, singleOffset.id.key)
        .bind(2, singleOffset.offsetStr)
        .bind(3, singleOffset.manifest)
        .bind(4, java.lang.Boolean.valueOf(singleOffset.mergeable))
        .bind(5, now)
    }

    val statements = storageReps match {
      case single: SingleOffset  => Vector(upsertStmt(single))
      case MultipleOffsets(many) => many.map(upsertStmt).toVector
    }

    R2dbcExecutor.updateInTx(statements).map(_ => Done)(ExecutionContext.parasitic)
  }

  def isDuplicate(record: Record): Boolean =
    getState().isDuplicate(record)

  def filterAccepted[Envelope](envelopes: immutable.Seq[Envelope]): Future[immutable.Seq[Envelope]] = {
    envelopes
      .foldLeft(Future.successful(getInflight(), Vector.empty[Envelope])) { (acc, envelope) =>
        acc.flatMap { case (inflight, filteredEnvelopes) =>
          createRecordWithOffset(envelope) match {
            case Some(recordWithOffset) =>
              isAccepted(recordWithOffset, inflight).map {
                case true =>
                  (
                    inflight.updated(recordWithOffset.record.pid, recordWithOffset.record.seqNr),
                    filteredEnvelopes :+ envelope)
                case false =>
                  (inflight, filteredEnvelopes)
              }
            case None =>
              Future.successful((inflight, filteredEnvelopes :+ envelope))
          }
        }
      }
      .map { case (_, filteredEnvelopes) =>
        filteredEnvelopes
      }
  }

  def isAccepted[Envelope](envelope: Envelope): Future[Boolean] = {
    createRecordWithOffset(envelope) match {
      case Some(recordWithOffset) => isAccepted(recordWithOffset, getInflight())
      case None                   => FutureTrue
    }
  }

  private def isAccepted[Envelope](
      recordWithOffset: RecordWithOffset,
      currentInflight: Map[Pid, SeqNr]): Future[Boolean] = {
    val pid = recordWithOffset.record.pid
    val seqNr = recordWithOffset.record.seqNr
    val currentState = getState()

    val duplicate = isDuplicate(recordWithOffset.record)

    if (duplicate) {
      logger.debug("Filtering out duplicate sequence number [{}] for pid [{}]", seqNr, pid) // FIXME change to trace
      FutureFalse
    } else if (recordWithOffset.strictSeqNr) {
      // strictSeqNr == true is for event sourced
      val prevSeqNr = currentInflight.getOrElse(pid, currentState.byPid.get(pid).map(_.seqNr).getOrElse(0L))
      if (prevSeqNr > 0) {
        // expecting seqNr to be +1 of previously known
        val ok = seqNr == prevSeqNr + 1
        if (ok) {
          FutureTrue
        } else {
          logger.info(
            "Rejecting unexpected sequence number [{}] for pid [{}], previous sequence number [{}]",
            seqNr,
            pid,
            prevSeqNr)
          FutureFalse
        }
      } else if (seqNr == 1) {
        // always accept first event if no other event for that pid has been seen
        FutureTrue
      } else {
        // Haven't see seen this pid within the time window. Since events can be missed
        // when read at the tail we will only accept it if the event with previous seqNr has timestamp
        // before the time window of the offset store.
        // Backtracking will emit missed event again.
        eventTimestampQuery.timestampOf(pid, seqNr - 1).map {
          case Some(previousTimestamp) =>
            if (previousTimestamp.isBefore(currentState.latestTimestamp.minus(settings.timeWindow)))
              true
            else {
              logger.info("Rejecting unknown sequence number (might be accepted later): {}", recordWithOffset)
              false
            }
          case None =>
            // previous not found, could have been deleted
            true
        }
      }
    } else {
      // strictSeqNr == false is for durable state where each revision might not be visible
      val prevSeqNr = currentInflight.getOrElse(pid, currentState.byPid.get(pid).map(_.seqNr).getOrElse(0L))
      val ok = seqNr > prevSeqNr

      if (ok) {
        FutureTrue
      } else {
        logger.debug("Filtering out earlier revision [{}] for pid [{}], previous revision [{}]", seqNr, pid, prevSeqNr)
        FutureFalse
      }
    }
  }

  @tailrec final def addInflight[Envelope](envelope: Envelope): Unit = {
    createRecordWithOffset(envelope) match {
      case Some(recordWithOffset) =>
        val currentInflight = getInflight()
        val newInflight = currentInflight.updated(recordWithOffset.record.pid, recordWithOffset.record.seqNr)
        if (!inflight.compareAndSet(currentInflight, newInflight))
          addInflight(envelope) // CAS retry, concurrent update of inflight
      case None =>
    }
  }

  @tailrec final def addInflights[Envelope](envelopes: immutable.Seq[Envelope]): Unit = {
    val currentInflight = getInflight()
    val entries = envelopes.iterator.map(createRecordWithOffset).collect { case Some(r) =>
      r.record.pid -> r.record.seqNr
    }
    val newInflight = currentInflight ++ entries
    if (!inflight.compareAndSet(currentInflight, newInflight))
      addInflights(envelopes) // CAS retry, concurrent update of inflight
  }

  def isInflight[Envelope](envelope: Envelope): Boolean = {
    createRecordWithOffset(envelope) match {
      case Some(recordWithOffset) =>
        val pid = recordWithOffset.record.pid
        val seqNr = recordWithOffset.record.seqNr
        getInflight().get(pid) match {
          case Some(`seqNr`) => true
          case _             => false
        }
      case None => true
    }
  }

  def deleteOldTimestampOffsets(): Future[Int] = {
    val currentState = getState()
    if (currentState.window.compareTo(settings.timeWindow) < 0) {
      // it hasn't filled up the window yet
      Future.successful(0)
    } else {
      val until = currentState.latestTimestamp.minus(settings.timeWindow)
      val minSlice = timestampOffsetBySlicesSourceProvider.minSlice
      val maxSlice = timestampOffsetBySlicesSourceProvider.maxSlice
      val result = r2dbcExecutor.updateOne("delete timestamp offset") { conn =>
        conn
          .createStatement(deleteTimestampOffsetSql)
          .bind(0, minSlice)
          .bind(1, maxSlice)
          .bind(2, projectionId.name)
          .bind(3, until)
      }

      // FIXME would it be good to keep at least one record per slice that can be used as the
      // starting point for the slice if the slice ranges are changed?

      result.failed.foreach { exc =>
        logger.warn(
          "Failed to delete timestamp offset until [{}] for projection [{}]: {}",
          until,
          projectionId.id,
          exc.toString)
      }
      if (logger.isDebugEnabled)
        result.foreach { rows =>
          logger.debug(
            "Deleted [{}] timestamp offset rows until [{}] for projection [{}].",
            rows,
            until,
            projectionId.id)
        }

      result
    }
  }

  def dropIfExists(): Future[Done] = {
    // FIXME not implemented yet
    Future.successful(Done)
  }

  def createIfNotExists(): Future[Done] = {
    // FIXME not implemented yet
    Future.successful(Done)
  }

  def clearOffset(): Future[Done] = {
    r2dbcExecutor
      .updateOne("clear offset") { conn =>
        logger.debug("clearing offset for [{}]", projectionId)
        conn
          .createStatement(clearOffsetSql)
          .bind(0, projectionId.name)
          .bind(1, projectionId.key)
      }
      .map { n =>
        logger.debug(s"clearing offset for [{}] - executed statement returned [{}]", projectionId, n)
        Done
      }
  }

  def readManagementState(): Future[Option[ManagementState]] = {
    Future.successful(None) // FIXME not implemented yet
  }

  def savePaused(paused: Boolean): Future[Done] = {
    Future.successful(Done) // FIXME not implemented yet
  }

  private def createRecordWithOffset[Envelope](envelope: Envelope): Option[RecordWithOffset] = {
    envelope match {
      case eventEnvelope: EventEnvelope[_] if eventEnvelope.offset.isInstanceOf[TimestampOffset] =>
        val timestampOffset = eventEnvelope.offset.asInstanceOf[TimestampOffset]
        Some(
          RecordWithOffset(
            Record(eventEnvelope.persistenceId, eventEnvelope.sequenceNr, timestampOffset.timestamp),
            timestampOffset,
            strictSeqNr = true))
      case change: UpdatedDurableState[_] if change.offset.isInstanceOf[TimestampOffset] =>
        val timestampOffset = change.offset.asInstanceOf[TimestampOffset]
        Some(
          RecordWithOffset(
            Record(change.persistenceId, change.revision, timestampOffset.timestamp),
            timestampOffset,
            strictSeqNr = false))
      case change: DurableStateChange[_] if change.offset.isInstanceOf[TimestampOffset] =>
        // FIXME case DeletedDurableState when that is added
        throw new IllegalArgumentException(
          s"DurableStateChange [${change.getClass.getName}] not implemented yet. Please report bug at https://github.com/akka/akka-persistence-r2dbc/issues")
      case _ => None
    }
  }

}
