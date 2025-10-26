package com.mycompany.worker.db

import com.mycompany.worker.{Worker, WorkerError}
import com.mycompany.worker.internal.HeartbeatStore
import zio.*
import javax.sql.DataSource
import java.sql.{Connection, ResultSet, Timestamp}
import java.time.{Instant, Duration}

/**
 * PostgreSQL implementation of HeartbeatStore.
 *
 * Returns typed WorkerError for all operations.
 *
 * Schema:
 * ```sql
 * CREATE TABLE worker_heartbeats (
 *   worker_id TEXT NOT NULL,
 *   timestamp TIMESTAMP NOT NULL,
 *   PRIMARY KEY (worker_id, timestamp)
 * );
 *
 * CREATE INDEX idx_worker_heartbeats_timestamp ON worker_heartbeats(timestamp DESC);
 * ```
 */
final case class PostgresHeartbeatStore(dataSource: DataSource)
    extends HeartbeatStore:

  override def record(id: Worker.Id, timestamp: Instant): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = """
            INSERT INTO worker_heartbeats (worker_id, timestamp)
            VALUES (?, ?)
            ON CONFLICT (worker_id, timestamp) DO NOTHING
          """
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, id.value)
          stmt.setTimestamp(2, Timestamp.from(timestamp))
          stmt.executeUpdate()
        }
      }
      .mapError(e => WorkerError.StorageError("record", e.getMessage))

  override def getLastHeartbeat(
      id: Worker.Id
  ): IO[WorkerError, Option[Instant]] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = """
            SELECT timestamp FROM worker_heartbeats
            WHERE worker_id = ?
            ORDER BY timestamp DESC
            LIMIT 1
          """
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, id.value)
          val rs = stmt.executeQuery()
          if rs.next() then Some(rs.getTimestamp("timestamp").toInstant)
          else None
        }
      }
      .mapError(e => WorkerError.StorageError("getLastHeartbeat", e.getMessage))

  override def remove(id: Worker.Id): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = "DELETE FROM worker_heartbeats WHERE worker_id = ?"
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, id.value)
          stmt.executeUpdate()
        }
      }
      .mapError(e => WorkerError.StorageError("remove", e.getMessage))

  override def findStaleWorkers(
      timeout: Duration
  ): IO[WorkerError, List[Worker.Id]] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = """
            SELECT DISTINCT worker_id
            FROM worker_heartbeats hb1
            WHERE timestamp = (
              SELECT MAX(timestamp)
              FROM worker_heartbeats hb2
              WHERE hb2.worker_id = hb1.worker_id
            )
            AND timestamp < ?
          """
          val stmt = conn.prepareStatement(sql)
          val cutoff = Instant.now().minus(timeout)
          stmt.setTimestamp(1, Timestamp.from(cutoff))
          val rs = stmt.executeQuery()
          val workerIds = scala.collection.mutable.ListBuffer[Worker.Id]()
          while rs.next() do workerIds += Worker.Id(rs.getString("worker_id"))
          workerIds.toList
        }
      }
      .mapError(e => WorkerError.StorageError("findStaleWorkers", e.getMessage))

  override def getHistory(
      id: Worker.Id,
      limit: Int
  ): IO[WorkerError, List[Instant]] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = """
            SELECT timestamp FROM worker_heartbeats
            WHERE worker_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
          """
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, id.value)
          stmt.setInt(2, limit)
          val rs = stmt.executeQuery()
          val timestamps = scala.collection.mutable.ListBuffer[Instant]()
          while rs.next() do timestamps += rs.getTimestamp("timestamp").toInstant
          timestamps.toList
        }
      }
      .mapError(e => WorkerError.StorageError("getHistory", e.getMessage))

  // ========== Private Helper Methods ==========

  private def useConnection[A](f: Connection => A): A =
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()

object PostgresHeartbeatStore:
  def layer: ZLayer[DataSource, Nothing, HeartbeatStore] =
    ZLayer.fromFunction(PostgresHeartbeatStore.apply)
