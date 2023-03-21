package io.skiplabs.skgw

import com.beust.klaxon.Klaxon
import com.beust.klaxon.TypeAdapter
import com.beust.klaxon.TypeFor
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.PathTemplateHandler
import io.undertow.util.PathTemplateMatch
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.AbstractReceiveListener
import io.undertow.websockets.core.BufferedTextMessage
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import io.undertow.websockets.spi.WebSocketHttpExchange
import java.nio.channels.Channel
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.bufferedWriter
import kotlin.reflect.KClass
import kotlin.system.exitProcess
import org.xnio.ChannelListener

val SERVICE_MGMT_DB_NAME = "skdb_service_mgmt"

class ProtoTypeAdapter : TypeAdapter<ProtoMessage> {
  override fun classFor(type: Any): KClass<out ProtoMessage> =
      when (type as String) {
        "auth" -> ProtoAuth::class
        "query" -> ProtoQuery::class
        "tail" -> ProtoTail::class
        "schema" -> ProtoSchemaQuery::class
        "write" -> ProtoWrite::class
        "pipe" -> ProtoData::class
        "createDatabase" -> ProtoCreateDb::class
        "credentials" -> ProtoCredentials::class
        "createUser" -> ProtoCreateUser::class
        else -> throw IllegalArgumentException("Unknown request type: $type")
      }
}

@TypeFor(field = "request", adapter = ProtoTypeAdapter::class)
sealed class ProtoMessage(val request: String)

data class ProtoAuth(
    val accessKey: String,
    val date: String,
    val nonce: String,
    val signature: String,
    val deviceUuid: String,
) : ProtoMessage("auth")

data class ProtoQuery(val query: String, val format: String = "csv") : ProtoMessage("query")

data class ProtoTail(
    val table: String,
    val since: Int = 0,
) : ProtoMessage("tail")

data class ProtoSchemaQuery(
    val table: String? = null,
    val view: String? = null,
    val suffix: String = ""
) : ProtoMessage("schema")

data class ProtoWrite(val table: String) : ProtoMessage("write")

data class ProtoCreateDb(val name: String) : ProtoMessage("createDatabase")

class ProtoCreateUser() : ProtoMessage("createUser")

data class ProtoData(val data: String) : ProtoMessage("pipe")

data class ProtoError(val code: String, val msg: String, val retryable: Boolean) :
    ProtoMessage("error")

data class ProtoCredentials(val accessKey: String, val privateKey: String) :
    ProtoMessage("credentials") {

  override fun toString(): String {
    return "ProtoCredentials(accessKey=${accessKey}, privateKey=**redacted**)"
  }
}

data class Credentials(
    val accessKey: String,
    val privateKey: ByteArray,
    val encryptedPrivateKey: ByteArray
) {
  fun b64plaintextKey(): String = Base64.getEncoder().encodeToString(privateKey)
  fun b64encryptedKey(): String = Base64.getEncoder().encodeToString(encryptedPrivateKey)
  fun clear(): Unit {
    privateKey.fill(0)
    encryptedPrivateKey.fill(0)
  }
  fun toProtoCredentials(): ProtoCredentials {
    return ProtoCredentials(accessKey, b64plaintextKey())
  }
  override fun toString(): String {
    return "Credentials(accessKey=${accessKey}, privateKey=**redacted**)"
  }
}

fun parse(data: String): ProtoMessage {
  val msg = Klaxon().parse<ProtoMessage>(data)
  if (msg == null) {
    throw RuntimeException("could not parse message")
  }
  return msg
}

fun serialise(msg: ProtoMessage): String {
  return Klaxon().toJsonString(msg)
}

val maxConnectionDuration: Duration = Duration.ofMinutes(10)

sealed interface Conn {

  fun handleMessage(request: ProtoMessage, channel: WebSocketChannel): Conn
  fun handleMessage(message: String, channel: WebSocketChannel) =
      handleMessage(parse(message), channel)

  fun close(): Conn
}

class EstablishedConn(val proc: Process, val authenticatedAt: Instant) : Conn {

  private fun unexpectedMsg(channel: WebSocketChannel): Conn {
    if (channel.isOpen()) {
      val msg = "unexpected request on established connection"
      WebSockets.sendTextBlocking(serialise(ProtoError("protocol", msg, false)), channel)
      WebSockets.sendCloseBlocking(1002, msg, channel)
      channel.close()
    }

    return ErroredConn()
  }

  override fun handleMessage(request: ProtoMessage, channel: WebSocketChannel): Conn {
    val now = Instant.now()
    if (Duration.between(authenticatedAt, now).abs().compareTo(maxConnectionDuration) > 0) {
      WebSockets.sendCloseBlocking(1011, "session timeout", channel)
      channel.close()
      return ClosedConn()
    }

    when (request) {
      is ProtoData -> {
        val stdin = proc.outputStream
        if (stdin == null) {
          throw RuntimeException("data received on a connection without a process setup")
        }
        // stream data to the process attached to this channel
        val writer = stdin.bufferedWriter()
        writer.write(request.data)
        writer.flush()
        return this
      }
      else -> return unexpectedMsg(channel)
    }
  }

  override fun close(): Conn {
    this.proc.outputStream?.close()
    return ClosedConn()
  }
}

fun genAccessKey(): String {
  val csrng = SecureRandom()
  val keyLength = 20
  // build a string of keyLength chars: 0-9a-zA-Z, which is 62 symbols
  val ints = csrng.ints(keyLength.toLong(), 0, 62)
  val codePoints =
      ints
          .map({
            when {
              it < 10 -> it + 48 // offset for 0-9
              it < 10 + 26 -> (it - 10) + 65 // offset for A-Z
              else -> (it - 10 - 26) + 97 // offset for a-z
            }
          })
          .toArray()
  return String(codePoints, 0, keyLength)
}

fun genCredentials(accessKey: String, encryption: EncryptionTransform): Credentials {
  val csrng = SecureRandom()

  // generate a 256 bit random key for the root user
  val plaintextRootKey = ByteArray(32)
  csrng.nextBytes(plaintextRootKey)
  val encryptedRootKey = encryption.encrypt(plaintextRootKey)
  val creds = Credentials(accessKey, plaintextRootKey, encryptedRootKey)

  return creds
}

fun createDb(dbName: String, encryption: EncryptionTransform): ProtoCredentials {
  val creds = genCredentials("root", encryption)
  createSkdb(dbName, creds.b64encryptedKey())
  val protoCreds = creds.toProtoCredentials()
  creds.clear()
  return protoCreds
}

class AuthenticatedConn(
    val skdb: Skdb,
    val accessKey: String,
    val authenticatedAt: Instant,
    val encryption: EncryptionTransform,
    val replicationId: String,
) : Conn {

  private fun unexpectedMsg(channel: WebSocketChannel, msg: String): Conn {
    if (channel.isOpen()) {
      WebSockets.sendTextBlocking(serialise(ProtoError("protocol", msg, false)), channel)
      WebSockets.sendCloseBlocking(1002, msg, channel)
      channel.close()
    }

    return ErroredConn()
  }

  override fun handleMessage(request: ProtoMessage, channel: WebSocketChannel): Conn {
    val now = Instant.now()
    if (Duration.between(authenticatedAt, now).abs().compareTo(maxConnectionDuration) > 0) {
      WebSockets.sendCloseBlocking(1011, "session timeout", channel)
      channel.close()
      return ClosedConn()
    }

    when (request) {
      is ProtoQuery -> {
        val format =
            when (request.format) {
              "csv" -> OutputFormat.CSV
              "json" -> OutputFormat.JSON
              "raw" -> OutputFormat.RAW
              else -> OutputFormat.CSV
            }
        val result = skdb.sql(request.query, format)
        val payload =
            if (result.exitSuccessfully()) {
              serialise(ProtoData(result.output))
            } else {
              serialise(ProtoError("query", result.output, true))
            }
        WebSockets.sendTextBlocking(payload, channel)
        return this
      }
      is ProtoSchemaQuery -> {
        val result =
            if (request.table != null) {
              skdb.dumpTable(request.table, request.suffix)
            } else if (request.view != null) {
              skdb.dumpView(request.view)
            } else {
              skdb.dumpSchema()
            }
        val payload =
            if (result.exitSuccessfully()) {
              serialise(ProtoData(result.output))
            } else {
              serialise(ProtoError("query", result.output, true))
            }
        WebSockets.sendTextBlocking(payload, channel)
        return this
      }
      is ProtoCreateDb -> {
        // this side effect is only authorized if you're connected as a service mgmt db user
        if (skdb.name != SERVICE_MGMT_DB_NAME) {
          return unexpectedMsg(channel, "error") // deliberately unhelpful error
        }
        val creds = createDb(request.name, encryption)
        val payload = serialise(creds)
        WebSockets.sendTextBlocking(payload, channel)
        return this
      }
      is ProtoCreateUser -> {
        val creds = genCredentials(genAccessKey(), encryption)
        skdb.createUser(creds.accessKey, creds.b64encryptedKey())
        val payload = serialise(creds.toProtoCredentials())
        creds.clear()
        WebSockets.sendTextBlocking(payload, channel)
        return this
      }
      is ProtoTail -> {
        val proc =
            skdb.tail(
                accessKey,
                request.table,
                request.since,
                replicationId,
                {
                  if (channel.isOpen()) {
                    val payload = serialise(ProtoData(it))
                    WebSockets.sendTextBlocking(payload, channel)
                  }
                },
                {
                  if (channel.isOpen()) {
                    val msg = "Unexpected EOF"
                    WebSockets.sendTextBlocking(serialise(ProtoError("tail", msg, true)), channel)
                    WebSockets.sendCloseBlocking(1011, msg, channel)
                    channel.close()
                  }
                },
            )
        return EstablishedConn(proc, authenticatedAt)
      }
      is ProtoWrite -> {
        val proc =
            skdb.writeCsv(
                accessKey,
                request.table,
                replicationId,
                {
                  if (channel.isOpen()) {
                    val payload = serialise(ProtoData(it))
                    WebSockets.sendTextBlocking(payload, channel)
                  }
                },
                {
                  if (channel.isOpen()) {
                    val msg = "Unexpected EOF"
                    WebSockets.sendTextBlocking(serialise(ProtoError("write", msg, true)), channel)
                    WebSockets.sendCloseBlocking(1011, msg, channel)
                    channel.close()
                  }
                })
        return EstablishedConn(proc, authenticatedAt)
      }
      is ProtoAuth -> {
        return unexpectedMsg(channel, "unexpected re-auth on established connection")
      }
      is ProtoData -> {
        return unexpectedMsg(channel, "unexpected data on non-established connection")
      }
      else -> return unexpectedMsg(channel, "unexpected message")
    }
  }

  override fun close(): Conn {
    return ClosedConn()
  }
}

class UnauthenticatedConn(
    val skdb: Skdb,
    val encryption: EncryptionTransform,
    val deviceUuidToReplicationSource: ConcurrentMap<String, String>
) : Conn {

  private fun unexpectedMsg(channel: WebSocketChannel): Conn {
    if (channel.isOpen()) {
      val msg = "Connection not yet authenticated"
      WebSockets.sendTextBlocking(serialise(ProtoError("protocol", msg, false)), channel)
      WebSockets.sendCloseBlocking(1002, msg, channel)
      channel.close()
    }

    return ErroredConn()
  }

  private fun verify(request: ProtoAuth): Boolean {
    val algo = "HmacSHA256"

    val now = Instant.now()
    val d = Instant.parse(request.date)
    // delta represents physical timeline time, regardless of calendars and clock shifts
    val delta = Duration.between(d, now)

    // do not allow requests that were not recent. the margin is for clock skew.
    if (delta.abs().compareTo(Duration.ofMinutes(10)) > 0) {
      return false
    }

    // TODO: check nonce against a cache to prevent replay attacks

    val content: String = request.request + request.accessKey + request.date + request.nonce
    val contentBytes = content.toByteArray(Charsets.UTF_8)

    val mac = Mac.getInstance(algo)
    val encryptedPrivateKey = skdb.privateKeyAsStored(request.accessKey)
    val privateKey = encryption.decrypt(encryptedPrivateKey)
    mac.init(SecretKeySpec(privateKey, algo))
    val ourSig = mac.doFinal(contentBytes)
    // at least try to keep the private key in memory for as little time as possible
    privateKey.fill(0)

    val b64sig = Base64.getEncoder().encodeToString(ourSig)

    return b64sig == request.signature
  }

  override fun handleMessage(request: ProtoMessage, channel: WebSocketChannel): Conn {
    when (request) {
      is ProtoAuth -> {
        if (!verify(request)) {
          if (channel.isOpen()) {
            val msg = "Authentication failed"
            WebSockets.sendTextBlocking(serialise(ProtoError("auth", msg, false)), channel)
            WebSockets.sendCloseBlocking(1002, msg, channel)
            channel.close()
          }

          return ErroredConn()
        }

        val replicationId =
            deviceUuidToReplicationSource.computeIfAbsent(
                request.deviceUuid, { skdb.uid().getOrThrow().trim() })

        return AuthenticatedConn(skdb, request.accessKey, Instant.now(), encryption, replicationId)
      }
      else -> return unexpectedMsg(channel)
    }
  }

  override fun close(): Conn {
    return this
  }
}

class ErroredConn() : Conn {
  override fun handleMessage(request: ProtoMessage, channel: WebSocketChannel): Conn {
    return this
  }

  override fun close(): Conn {
    return this
  }
}

class ClosedConn() : Conn {
  override fun handleMessage(request: ProtoMessage, channel: WebSocketChannel): Conn {
    return this
  }

  override fun close(): Conn {
    return this
  }
}

fun connectionHandler(
    taskPool: ScheduledExecutorService,
    encryption: EncryptionTransform,
    deviceUuidToReplicationSource: ConcurrentMap<String, String>,
): HttpHandler {
  return Handlers.websocket(
      object : WebSocketConnectionCallback {
        override fun onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel) {
          val pathParams = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY).getParameters()
          val db = pathParams["database"]
          val skdb = openSkdb(db)

          if (skdb == null) {
            // 1011 is internal error
            val msg = "Could not open database"
            WebSockets.sendTextBlocking(serialise(ProtoError("resource", msg, false)), channel)
            WebSockets.sendCloseBlocking(1011, msg, channel)
            channel.close()
            return
          }

          var conn: Conn = UnauthenticatedConn(skdb, encryption, deviceUuidToReplicationSource)

          val timeout =
              taskPool.schedule(
                  {
                    conn.close()
                    channel.close()
                  },
                  10,
                  TimeUnit.MINUTES)

          channel.receiveSetter.set(
              object : AbstractReceiveListener() {
                override fun onFullTextMessage(
                    channel: WebSocketChannel,
                    message: BufferedTextMessage
                ) {
                  try {
                    conn = conn.handleMessage(message.data, channel)
                  } catch (ex: Exception) {
                    // 1011 is internal error
                    val msg = "Internal error"
                    WebSockets.sendTextBlocking(
                        serialise(ProtoError("internal", msg, true)), channel)
                    WebSockets.sendCloseBlocking(1011, msg, channel)
                    channel.close()
                  }
                }
              })

          channel.closeSetter.set(
              object : ChannelListener<Channel> {
                override fun handleEvent(channel: Channel): Unit {
                  timeout.cancel(false)
                  conn = conn.close()
                  // TODO: wait and ensure the process ends, forcibly close on
                  // timeout
                }
              })

          channel.resumeReceives()
        }
      })
}

fun createHttpServer(connectionHandler: HttpHandler): Undertow {
  var pathHandler = PathTemplateHandler().add("/dbs/{database}/connection", connectionHandler)

  return Undertow.builder().addHttpListener(8080, "0.0.0.0").setHandler(pathHandler).build()
}

fun envIsSane(): Boolean {
  val svcSkdb = openSkdb(SERVICE_MGMT_DB_NAME)

  if (svcSkdb == null) {
    println("FAIL: Could not open service management database.")
    return false
  }

  val successfullyRead =
      svcSkdb
          .sql("SELECT COUNT(*) FROM skdb_users WHERE username = 'root';", OutputFormat.RAW)
          .getOrThrow()
          .trim() == "1"

  if (!successfullyRead) {
    println("FAIL: Could not read from service management database.")
  }

  return successfullyRead
}

fun main(args: Array<String>) {
  val arglist = args.toList()

  var encryption = ec2KmsEncryptionTransform()

  if (arglist.contains("--DANGEROUS-no-encryption")) {
    encryption = NoEncryptionTransform()
  }

  if (arglist.contains("--init")) {
    val creds = createDb(SERVICE_MGMT_DB_NAME, encryption)
    println("{\"${SERVICE_MGMT_DB_NAME}\": {\"${creds.accessKey}\": \"${creds.privateKey}\"}}")
    return
  }

  if (!envIsSane()) {
    println("Environment checks failed. Use --init for a cold start.")
    exitProcess(1)
  }

  val taskPool = Executors.newSingleThreadScheduledExecutor()
  val deviceUuidToReplicationSource = ConcurrentHashMap<String, String>()
  val connHandler = connectionHandler(taskPool, encryption, deviceUuidToReplicationSource)
  val server = createHttpServer(connHandler)
  server.start()
}
