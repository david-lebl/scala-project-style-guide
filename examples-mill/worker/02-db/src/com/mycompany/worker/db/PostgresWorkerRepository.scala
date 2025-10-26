package com.mycompany.worker.db

import com.mycompany.worker.{Worker, WorkerError}
import com.mycompany.worker.internal.WorkerRepository
import zio.*
import javax.sql.DataSource
import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}

/**
 * PostgreSQL implementation of WorkerRepository.
 *
 * Returns typed WorkerError for all operations.
 *
 * Schema:
 * ```sql
 * CREATE TABLE workers (
 *   id TEXT PRIMARY KEY,
 *   region TEXT NOT NULL,
 *   capacity INT NOT NULL,
 *   capabilities TEXT[],
 *   metadata JSONB,
 *   status TEXT NOT NULL,
 *   registered_at TIMESTAMP NOT NULL,
 *   last_heartbeat_at TIMESTAMP
 * );
 * ```
 */
final case class PostgresWorkerRepository(dataSource: DataSource)
    extends WorkerRepository:

  override def save(worker: Worker): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = """
            INSERT INTO workers (
              id, region, capacity, capabilities, metadata,
              status, registered_at, last_heartbeat_at
            )
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              region = EXCLUDED.region,
              capacity = EXCLUDED.capacity,
              capabilities = EXCLUDED.capabilities,
              metadata = EXCLUDED.metadata,
              status = EXCLUDED.status,
              last_heartbeat_at = EXCLUDED.last_heartbeat_at
          """
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, worker.id.value)
          stmt.setString(2, worker.config.region)
          stmt.setInt(3, worker.config.capacity)
          stmt.setArray(
            4,
            conn.createArrayOf("text", worker.config.capabilities.toArray)
          )
          stmt.setString(5, metadataToJson(worker.config.metadata))
          stmt.setString(6, worker.status.toString)
          stmt.setTimestamp(7, Timestamp.from(worker.registeredAt))
          worker.lastHeartbeatAt match
            case Some(ts) => stmt.setTimestamp(8, Timestamp.from(ts))
            case None     => stmt.setNull(8, java.sql.Types.TIMESTAMP)
          stmt.executeUpdate()
        }
      }
      .mapError(e => WorkerError.RepositoryError("save", e.getMessage))

  override def findById(id: Worker.Id): IO[WorkerError, Option[Worker]] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = "SELECT * FROM workers WHERE id = ?"
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, id.value)
          val rs = stmt.executeQuery()
          if rs.next() then Some(rowToWorker(rs))
          else None
        }
      }
      .mapError(e => WorkerError.RepositoryError("findById", e.getMessage))

  override def exists(id: Worker.Id): IO[WorkerError, Boolean] =
    findById(id).map(_.isDefined)

  override def delete(id: Worker.Id): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = "DELETE FROM workers WHERE id = ?"
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, id.value)
          val rowsAffected = stmt.executeUpdate()
          if rowsAffected == 0 then
            throw new RuntimeException(s"Worker not found: ${id.value}")
        }
      }
      .mapError(e => WorkerError.RepositoryError("delete", e.getMessage))

  override def getByStatus(
      status: Worker.Status
  ): IO[WorkerError, List[Worker]] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = "SELECT * FROM workers WHERE status = ?"
          val stmt = conn.prepareStatement(sql)
          stmt.setString(1, status.toString)
          val rs = stmt.executeQuery()
          val workers = scala.collection.mutable.ListBuffer[Worker]()
          while rs.next() do workers += rowToWorker(rs)
          workers.toList
        }
      }
      .mapError(e => WorkerError.RepositoryError("getByStatus", e.getMessage))

  override def getAll: IO[WorkerError, List[Worker]] =
    ZIO
      .attemptBlocking {
        useConnection { conn =>
          val sql = "SELECT * FROM workers"
          val stmt = conn.prepareStatement(sql)
          val rs = stmt.executeQuery()
          val workers = scala.collection.mutable.ListBuffer[Worker]()
          while rs.next() do workers += rowToWorker(rs)
          workers.toList
        }
      }
      .mapError(e => WorkerError.RepositoryError("getAll", e.getMessage))

  // ========== Private Helper Methods ==========

  private def useConnection[A](f: Connection => A): A =
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()

  private def rowToWorker(rs: ResultSet): Worker =
    val capabilities =
      Option(rs.getArray("capabilities"))
        .map(_.getArray.asInstanceOf[Array[String]].toSet)
        .getOrElse(Set.empty)

    val metadata = jsonToMetadata(rs.getString("metadata"))

    val config = Worker.Config(
      region = rs.getString("region"),
      capacity = rs.getInt("capacity"),
      capabilities = capabilities,
      metadata = metadata
    )

    val lastHeartbeat = Option(rs.getTimestamp("last_heartbeat_at"))
      .map(_.toInstant)

    Worker(
      id = Worker.Id(rs.getString("id")),
      config = config,
      status = Worker.Status.valueOf(rs.getString("status")),
      registeredAt = rs.getTimestamp("registered_at").toInstant,
      lastHeartbeatAt = lastHeartbeat
    )

  private def metadataToJson(metadata: Map[String, String]): String =
    // Simple JSON serialization (use zio-json in production)
    if metadata.isEmpty then "{}"
    else
      metadata
        .map { case (k, v) => s""""$k":"$v"""" }
        .mkString("{", ",", "}")

  private def jsonToMetadata(json: String): Map[String, String] =
    // Simple JSON deserialization (use zio-json in production)
    if json == null || json == "{}" then Map.empty
    else
      // Simplified parsing - use proper JSON library in production
      json
        .stripPrefix("{")
        .stripSuffix("}")
        .split(",")
        .map(_.split(":").map(_.trim.stripPrefix("\"").stripSuffix("\"")))
        .collect { case Array(k, v) => k -> v }
        .toMap

object PostgresWorkerRepository:
  def layer: ZLayer[DataSource, Nothing, WorkerRepository] =
    ZLayer.fromFunction(PostgresWorkerRepository.apply)
