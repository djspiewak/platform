package ygg.data

import java.nio.ByteBuffer
import java.util.concurrent.{ BlockingQueue, ArrayBlockingQueue, LinkedBlockingQueue }
import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.SoftReference

import ygg.common._
import scalaz._

/**
  * A `Monad` for working with `ByteBuffer`s.
  */
trait ByteBufferMonad[M[_]] extends Monad[M] {
  def getBuffer(min: Int): M[ByteBuffer]
}

final class ByteBufferPool(val capacity: Int) {
  private val fixedBufferCount = 8
  private val _hits            = new AtomicLong()
  private val _misses          = new AtomicLong()

  val fixedBufferQueue: BlockingQueue[ByteBuffer]               = new ArrayBlockingQueue(fixedBufferCount)
  val flexBufferQueue: BlockingQueue[SoftReference[ByteBuffer]] = new LinkedBlockingQueue()

  def hits   = _hits.get()
  def misses = _hits.get()

  /**
    * Returns a cleared `ByteBuffer` that can store `capacity` bytes.
    */
  def acquire: ByteBuffer = {
    var buffer = fixedBufferQueue.poll()

    if (buffer == null) {
      var ref = flexBufferQueue.poll()
      buffer = if (ref != null) ref.get else null
      while (ref != null && buffer == null) {
        ref = flexBufferQueue.poll()
        buffer = if (ref != null) ref.get else null
      }
    }

    if (buffer == null) {
      _misses.incrementAndGet()
      buffer = ByteBuffer.allocate(capacity)
    } else {
      _hits.incrementAndGet()
    }

    buffer.clear()
    buffer
  }

  /**
    * Releases a `ByteBuffer` back into the pool for re-use later on.
    */
  def release(buffer: ByteBuffer): Unit = {
    if (!(fixedBufferQueue offer buffer)) {
      flexBufferQueue offer (new SoftReference(buffer))
    }
  }

  def toStream: Stream[ByteBuffer] = Stream.continually(acquire)

  def run[A](a: ByteBufferPool.State[A]): A = a.eval((this, Nil))
}

object ByteBufferPool {
  type State[A] = scalaz.State[ByteBufferPool -> List[ByteBuffer], A]

  def apply(): ByteBufferPool = new ByteBufferPool(16 * 1024)

  implicit object ByteBufferPoolMonad extends ByteBufferMonad[ByteBufferPool.State] with Monad[ByteBufferPool.State] {

    def point[A](a: => A): ByteBufferPool.State[A] = State.state(a)

    def bind[A, B](fa: ByteBufferPool.State[A])(f: A => ByteBufferPool.State[B]): ByteBufferPool.State[B] =
      State(s =>
        fa(s) match {
          case (s, a) => f(a)(s)
      })

    def getBuffer(min: Int): ByteBufferPool.State[ByteBuffer] = ByteBufferPool.acquire(min)
  }

  /**
    * Acquire a `ByteBuffer` and add it to the state.
    */
  def acquire(min: Int): ByteBufferPool.State[ByteBuffer] = State {
    case (pool, buffers @ (buf :: _)) if buf.remaining() >= min =>
      ((pool, buffers), buf)

    case (pool, buffers) =>
      val buf = pool.acquire
      ((pool, buf :: buffers), buf)
  }

  def acquire: ByteBufferPool.State[ByteBuffer] = acquire(512)

  /**
    * Reverses the state (list of `ByteBuffer`s) and returns an `Array[Byte]` of
    * the contiguous bytes in all the buffers.
    */
  def flipBytes: ByteBufferPool.State[Array[Byte]] = State {
    case (pool, sreffub) =>
      val buffers = sreffub.reverse
      ((pool, buffers), getBytesFrom(buffers))
  }

  /**
    * Removes and releases all `ByteBuffer`s in the state to the pool.
    */
  def release: ByteBufferPool.State[Unit] = State {
    case (pool, buffers) =>
      buffers foreach (pool.release(_))
      ((pool, Nil), ())
  }

  def getBytesFrom(buffers: List[ByteBuffer]): Array[Byte] = {
    val size = buffers.foldLeft(0) { (size, buf) =>
      buf.flip()
      size + buf.remaining()
    }
    val bytes = new Array[Byte](size)
    buffers.foldLeft(0) { (offset, buf) =>
      val length = buf.remaining()
      buf.get(bytes, offset, length)
      offset + length
    }
    bytes
  }
}