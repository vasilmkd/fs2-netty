/*
 * Copyright 2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2
package netty

import cats.{Applicative, Functor}
import cats.effect.{Async, Poll, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.syntax.all._
import cats.syntax.all._

import com.comcast.ip4s.{IpAddress, SocketAddress}

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.channel.socket.SocketChannel

private final class SocketHandler[F[_]: Async] (
    disp: Dispatcher[F],
    channel: SocketChannel,
    bufs: Queue[F, AnyRef])     // ByteBuf | Throwable | Null
    extends ChannelInboundHandlerAdapter
    with Socket[F] {

  val localAddress: F[SocketAddress[IpAddress]] =
    Sync[F].delay(SocketAddress.fromInetSocketAddress(channel.localAddress()))

  val remoteAddress: F[SocketAddress[IpAddress]] =
    Sync[F].delay(SocketAddress.fromInetSocketAddress(channel.remoteAddress()))

  private[this] def take(poll: Poll[F]): F[Chunk[Byte]] =
    poll(bufs.take) flatMap {
      case null => Applicative[F].pure(null)   // EOF marker
      case c: Chunk[_] => c.asInstanceOf[Chunk[Byte]].pure[F]
      case t: Throwable => t.raiseError[F, Chunk[Byte]]
    }

  private[this] val fetch: F[Chunk[Byte]] =
    Async[F].uncancelable { poll =>
      Sync[F].delay(channel.read()) *> take(poll)
    }

  lazy val reads: Stream[F, Byte] =
    Stream force {
      Functor[F].ifF(isOpen)(Stream.eval(fetch).flatMap(b => if (b == null) Stream.empty else Stream.chunk(b)) ++ reads, Stream.empty)
    }

  def write(bytes: Chunk[Byte]): F[Unit] =
    fromNettyFuture[F](Sync[F].delay(channel.writeAndFlush(toByteBuf(bytes)))).void

  val writes: Pipe[F, Byte, INothing] =
    _.chunks.evalMap(c => write(c) *> isOpen).takeWhile(b => b).drain

  private[this] val isOpen: F[Boolean] =
    Sync[F].delay(channel.isOpen())

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) =
    msg match {
      case b: ByteBuf =>
        disp.unsafeRunAndForget {
          Sync[F].delay(toChunk(b)).flatMap(bufs.offer).guarantee(Sync[F].delay(b.release()).void)
        }
      case msg => disp.unsafeRunAndForget(bufs.offer(msg))
    }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) =
    disp.unsafeRunAndForget(bufs.offer(t))

  override def channelInactive(ctx: ChannelHandlerContext) =
    try {
      disp.unsafeRunAndForget(bufs.offer(null))
    } catch {
      case _: IllegalStateException => ()   // sometimes we can see this due to race conditions in shutdown
    }

  private[this] def toByteBuf(chunk: Chunk[Byte]): ByteBuf =
    chunk match {
      case Chunk.ArraySlice(arr, off, len) =>
        Unpooled.wrappedBuffer(arr, off, len)

      case c: Chunk.ByteBuffer =>
        Unpooled.wrappedBuffer(c.toByteBuffer)

      case c =>
        Unpooled.wrappedBuffer(c.toArray)
    }

  private[this] def toChunk(buf: ByteBuf): Chunk[Byte] =
    if (buf.hasArray())
      Chunk.array(buf.array())
    else if (buf.nioBufferCount() > 0)
      Chunk.byteBuffer(buf.nioBuffer())
    else
      ???
}

private object SocketHandler {
  def apply[F[_]: Async](disp: Dispatcher[F], channel: SocketChannel): F[SocketHandler[F]] =
    Queue.unbounded[F, AnyRef] map { bufs =>
      new SocketHandler(disp, channel, bufs)
    }
}
