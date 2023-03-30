package io.skiplabs.skgw

import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.AbstractReceiveListener
import io.undertow.websockets.core.BufferedBinaryMessage
import io.undertow.websockets.core.BufferedTextMessage
import io.undertow.websockets.core.CloseMessage
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import io.undertow.websockets.spi.WebSocketHttpExchange
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channel
import java.nio.charset.StandardCharsets
import org.xnio.ChannelListener

// TODO: this all has a blocking interface

interface MuxedSocketFactory {
  fun onConnect(exchange: WebSocketHttpExchange): MuxedSocket
}

class MuxedSocketEndpoint(val socketFactory: MuxedSocketFactory) : WebSocketConnectionCallback {

  override fun onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel) {
    val muxedSocket = socketFactory.onConnect(exchange)

    channel.receiveSetter.set(
        object : AbstractReceiveListener() {
          override fun onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) {
            throw RuntimeException("Unexpected text message received.")
          }
          override fun onFullBinaryMessage(
              channel: WebSocketChannel,
              message: BufferedBinaryMessage
          ) {
            val buf = WebSockets.mergeBuffers(*message.data.resource)
            muxedSocket.onSocketMessage(buf)
          }

          // websocket close or error
          override fun onCloseMessage(cm: CloseMessage, channel: WebSocketChannel) {
            if (cm.code == CloseMessage.NORMAL_CLOSURE) {
              muxedSocket.onSocketClose()
            } else {
              muxedSocket.onSocketError(0, "websocket closed")
            }
          }
        })

    // underlying tcp close
    channel.closeSetter.set(
        object : ChannelListener<Channel> {
          override fun handleEvent(channel: Channel): Unit {
            muxedSocket.onSocketError(0, "tcp socket closed")
          }
        })

    channel.resumeReceives()
  }
}

typealias onStreamFn = (Stream) -> Unit

typealias onDataFn = (ByteBuffer) -> Unit

typealias onCloseFn = () -> Unit

typealias onErrorFn = (Int, String) -> Unit

class MuxedSocket(
    val onStream: onStreamFn,
    val onClose: onCloseFn,
    val onError: onErrorFn,
    val socket: WebSocketChannel,
    private var state: State = State.IDLE,
    private var nextStream: Int = 1,
    private var clientStreamWatermark: Int = 0,
    private val activeStreams: MutableMap<Int, Stream> = HashMap(),
) {
  enum class State {
    IDLE,
    AUTH_RECV,
    CLOSING,
    CLOSE_WAIT,
    CLOSED
  }

  // user-facing interface /////////////////////////////////////////////////////

  fun openStream(): Stream? {
    when (state) {
      State.IDLE,
      State.CLOSE_WAIT -> {
        return null
      }
      State.CLOSED,
      State.CLOSING -> {
        throw RuntimeException("Connection closed")
      }
      State.AUTH_RECV -> {
        val stream = Stream(nextStream, this)
        activeStreams.put(nextStream, stream)
        nextStream = nextStream + 2
        return stream
      }
    }
  }

  fun closeSocket() {
    when (state) {
      State.CLOSING,
      State.CLOSED -> {}
      State.IDLE -> {
        activeStreams.clear()
        state = State.CLOSED
        WebSockets.sendCloseBlocking(CloseMessage.NORMAL_CLOSURE, "", socket)
        socket.close()
      }
      State.CLOSE_WAIT -> {
        for (stream in activeStreams.values) {
          stream.close()
        }
        activeStreams.clear()
        state = State.CLOSED
        WebSockets.sendCloseBlocking(CloseMessage.NORMAL_CLOSURE, "", socket)
        socket.close()
      }
      State.AUTH_RECV -> {
        for (stream in activeStreams.values) {
          stream.close()
        }
        state = State.CLOSING
        WebSockets.sendCloseBlocking(CloseMessage.NORMAL_CLOSURE, "", socket)
        socket.close()
      }
    }
  }

  fun errorSocket(errorCode: Int, msg: String) {
    when (state) {
      State.IDLE,
      State.CLOSING,
      State.CLOSED -> {
        activeStreams.clear()
        state = State.CLOSED
      }
      State.CLOSE_WAIT,
      State.AUTH_RECV -> {
        for (stream in activeStreams.values) {
          stream.error(errorCode, msg)
        }
        activeStreams.clear()
        state = State.CLOSED
        val lastStream = Math.max(nextStream - 2, clientStreamWatermark)
        WebSockets.sendBinaryBlocking(encodeGoawayMsg(lastStream, errorCode, msg), socket)
        WebSockets.sendCloseBlocking(CloseMessage.PROTOCOL_ERROR, msg, socket)
        socket.close()
      }
    }
  }

  // interface used by WS //////////////////////////////////////////////////////

  fun onSocketMessage(msg: ByteBuffer) {
    when (state) {
      State.CLOSE_WAIT,
      State.CLOSED -> {}
      State.IDLE -> {
        // TODO: check auth and transition to auth_recv, anything
        // else: fail the socket, setting to closed
      }
      State.AUTH_RECV,
      State.CLOSING -> {
        // TODO: decode, dispatch, being careful about new streams when closing
      }
    }
  }

  fun onSocketClose() {
    when (state) {
      State.CLOSED,
      State.CLOSE_WAIT -> {}
      State.IDLE,
      State.AUTH_RECV -> {
        for (stream in activeStreams.values) {
          stream.onStreamClose()
        }
        onClose()
        state = State.CLOSE_WAIT
      }
      State.CLOSING -> {
        for (stream in activeStreams.values) {
          stream.onStreamClose()
        }
        onClose()
        activeStreams.clear()
        state = State.CLOSED
      }
    }
  }
  // mux gowaway, websocket close with error code, or tcp close
  fun onSocketError(errorCode: Int, msg: String) {
    when (state) {
      State.CLOSED -> {}
      State.IDLE,
      State.CLOSE_WAIT,
      State.AUTH_RECV,
      State.CLOSING -> {
        for (stream in activeStreams.values) {
          stream.onStreamError(errorCode, msg)
        }
        onError(errorCode, msg)
        activeStreams.clear()
        state = State.CLOSED
      }
    }
  }

  // interface used by Stream //////////////////////////////////////////////////

  fun streamClose(streamId: Int, nowClosed: Boolean) {
    when (state) {
      State.IDLE,
      State.CLOSING,
      State.CLOSED -> {}
      State.CLOSE_WAIT,
      State.AUTH_RECV -> {
        WebSockets.sendBinaryBlocking(encodeStreamCloseMsg(streamId), socket)
        if (nowClosed) {
          activeStreams.remove(streamId)
        }
      }
    }
  }

  fun streamError(streamId: Int, errorCode: Int, msg: String) {
    when (state) {
      State.IDLE,
      State.CLOSING,
      State.CLOSED -> {}
      State.CLOSE_WAIT,
      State.AUTH_RECV -> {
        WebSockets.sendBinaryBlocking(encodeStreamErrorMsg(streamId, errorCode, msg), socket)
        activeStreams.remove(streamId)
      }
    }
  }

  fun streamSend(streamId: Int, data: ByteBuffer) {
    when (state) {
      State.IDLE,
      State.CLOSING,
      State.CLOSED -> {}
      State.CLOSE_WAIT,
      State.AUTH_RECV -> {
        WebSockets.sendBinaryBlocking(encodeStreamDataMsg(streamId, data), socket)
      }
    }
  }

  // internal //////////////////////////////////////////////////////////////////

  fun encodeGoawayMsg(lastStream: Int, errorCode: Int, msg: String): ByteBuffer {
    if (lastStream > 0xFFFFFF) {
      throw IllegalArgumentException("lastStream too large")
    }
    val encoder = StandardCharsets.UTF_8.newEncoder()
    val buf = ByteBuffer.allocate(16 + msg.length * 3)
    buf.putInt(0x01000000) // type 1 and stream 0
    buf.putInt(lastStream)
    buf.putInt(errorCode)
    buf.putInt(0) // msg size placeholder - moves cursor

    var res = encoder.encode(CharBuffer.wrap(msg), buf, true)
    res.throwException()
    res = encoder.flush(buf)
    res.throwException()

    buf.putInt(12, buf.position() - 16) // go back and fill msg size

    return buf.flip()
  }

  fun encodeStreamDataMsg(stream: Int, data: ByteBuffer): ByteBuffer {
    if (stream > 0xFFFFFF) {
      throw IllegalArgumentException("stream too large")
    }
    val buf = ByteBuffer.allocate(4)
    buf.putInt((0x02 shl 24) or stream) // type and stream
    buf.put(data)
    return buf.flip()
  }

  fun encodeStreamCloseMsg(stream: Int): ByteBuffer {
    if (stream > 0xFFFFFF) {
      throw IllegalArgumentException("stream too large")
    }
    val buf = ByteBuffer.allocate(4)
    buf.putInt((0x03 shl 24) or stream) // type and stream
    return buf.flip()
  }

  fun encodeStreamErrorMsg(stream: Int, errorCode: Int, msg: String): ByteBuffer {
    if (stream > 0xFFFFFF) {
      throw IllegalArgumentException("stream too large")
    }
    val encoder = StandardCharsets.UTF_8.newEncoder()
    val buf = ByteBuffer.allocate(12 + msg.length * 3)
    buf.putInt((0x04 shl 24) or stream) // type 4 and stream
    buf.putInt(errorCode)
    buf.putInt(0) // msg size placeholder - moves cursor

    var res = encoder.encode(CharBuffer.wrap(msg), buf, true)
    res.throwException()
    res = encoder.flush(buf)
    res.throwException()

    buf.putInt(8, buf.position() - 12) // go back and fill msg size

    return buf.flip()
  }
}

class Stream(
    val streamId: Int,
    val socket: MuxedSocket,
    var onData: onDataFn = {},
    var onClose: onCloseFn = {},
    var onError: onErrorFn = { _, _ -> },
    private var state: State = State.OPEN,
) {
  enum class State {
    OPEN,
    CLOSING,
    CLOSEWAIT,
    CLOSED,
  }

  fun close() {
    when (state) {
      State.CLOSING,
      State.CLOSED -> {}
      State.OPEN -> {
        state = State.CLOSING
        socket.streamClose(streamId, nowClosed = false)
      }
      State.CLOSEWAIT -> {
        state = State.CLOSED
        socket.streamClose(streamId, nowClosed = true)
      }
    }
  }

  fun error(errorCode: Int, msg: String) {
    when (state) {
      State.CLOSING,
      State.CLOSED -> {
        state = State.CLOSED
      }
      State.OPEN,
      State.CLOSEWAIT -> {
        state = State.CLOSED
        socket.streamError(streamId, errorCode, msg)
      }
    }
  }

  fun send(data: ByteBuffer) {
    when (state) {
      State.CLOSING,
      State.CLOSED -> {}
      State.OPEN,
      State.CLOSEWAIT -> {
        socket.streamSend(streamId, data)
      }
    }
  }

  fun onStreamClose(): Boolean {
    return when (state) {
      State.CLOSED -> true
      State.CLOSEWAIT -> false
      State.CLOSING -> {
        state = State.CLOSED
        onClose()
        true
      }
      State.OPEN -> {
        state = State.CLOSEWAIT
        onClose()
        false
      }
    }
  }

  fun onStreamError(errorCode: Int, msg: String) {
    when (state) {
      State.CLOSED -> {}
      State.CLOSEWAIT,
      State.CLOSING,
      State.OPEN -> {
        state = State.CLOSED
        onError(errorCode, msg)
      }
    }
  }

  fun onStreamData(data: ByteBuffer) {
    when (state) {
      State.CLOSED,
      State.CLOSEWAIT -> {}
      State.CLOSING,
      State.OPEN -> {
        onData(data)
      }
    }
  }
}
