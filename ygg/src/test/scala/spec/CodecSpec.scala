/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package ygg.tests

import blueeyes._
import com.precog.common._
import com.precog.util.ByteBufferPool
import org.scalacheck.Shrink
import ygg.data._
import TestSupport._
import ByteBufferPool._

class CodecSpec extends quasar.Qspec {
  val pool      = ByteBufferPool()
  val smallPool = new ByteBufferPool(10)

  implicit def arbBitSet: Arbitrary[BitSet] = Arbitrary(Gen.listOf(Gen.choose(0, 500)) map BitSetUtil.create)

  implicit def arbSparseBitSet: Arbitrary[Codec[BitSet] -> BitSet] = {
    Arbitrary(Gen.chooseNum(0, 500) flatMap { size =>
      val codec = Codec.SparseBitSetCodec(size)
      if (size > 0) {
        Gen.listOf(Gen.choose(0, size - 1)) map { bits =>
          //(codec, BitSet(bits: _*))
          (codec, BitSetUtil.create(bits))
        }
      } else {
        Gen.const((codec, new BitSet()))
      }
    })
  }

  implicit def arbSparseRawBitSet: Arbitrary[Codec[RawBitSet] -> RawBitSet] = {
    Arbitrary(Gen.chooseNum(0, 500) flatMap { size =>
      val codec = Codec.SparseRawBitSetCodec(size)
      if (size > 0) {
        Gen.listOf(Gen.choose(0, size - 1)) map { bits =>
          //(codec, BitSet(bits: _*))
          val bs = RawBitSet.create(size)
          bits foreach { RawBitSet.set(bs, _) }
          (codec, bs)
        }
      } else {
        Gen.const((codec, RawBitSet.create(0)))
      }
    })
  }

  def surviveEasyRoundTrip[A](a: A)(implicit codec: Codec[A]) = {
    val buf = pool.acquire
    codec.writeUnsafe(a, buf)
    buf.flip()
    codec.read(buf) must_== a
  }
  def surviveHardRoundTrip[A](a: A)(implicit codec: Codec[A]) = {
    val bytes = smallPool.run(for {
      _     <- codec.write(a)
      bytes <- flipBytes
      _     <- release
    } yield bytes)
    bytes.length must_== codec.encodedSize(a)
    codec.read(ByteBufferWrap(bytes)) must_== a
  }
  def surviveRoundTrip[A](codec: Codec[A])(implicit a: Arbitrary[A], s: Shrink[A]) = "survive round-trip" should {
    "with large buffers" in {
      prop { (a: A) =>
        surviveEasyRoundTrip(a)(codec)
      }
    }
    "with small buffers" in {
      prop { (a: A) =>
        surviveHardRoundTrip(a)(codec)
      }
    }
  }

  "constant codec" should {
    "write 0 bytes" in {
      val codec = Codec.ConstCodec(true)
      codec.encodedSize(true) must_== 0
      codec.read(ByteBufferWrap(new Array[Byte](0))) must_== true
      codec.writeUnsafe(true, java.nio.ByteBuffer.allocate(0))
      ok
    }
  }
  "LongCodec" should surviveRoundTrip(Codec.LongCodec)
  "PackedLongCodec" should surviveRoundTrip(Codec.PackedLongCodec)
  "BooleanCodec" should surviveRoundTrip(Codec.BooleanCodec)
  "DoubleCodec" should surviveRoundTrip(Codec.DoubleCodec)
  "Utf8Codec" should surviveRoundTrip(Codec.Utf8Codec)
  "BigDecimalCodec" should surviveRoundTrip(Codec.BigDecimalCodec)
  "BitSetCodec" should surviveRoundTrip(Codec.BitSetCodec)
  "SparseBitSet" should {
    "survive round-trip" should {
      "with large buffers" in {
        prop { (sparse: (Codec[BitSet], BitSet)) =>
          surviveEasyRoundTrip(sparse._2)(sparse._1)
        }
      }
      "with small buffers" in {
        prop { (sparse: (Codec[BitSet], BitSet)) =>
          surviveHardRoundTrip(sparse._2)(sparse._1)
        }
      }
    }
  }
  "SparseRawBitSet" should {
    "survive round-trip" should {
      "with large buffers" in {
        prop { (sparse: (Codec[RawBitSet], RawBitSet)) =>
          surviveEasyRoundTrip(sparse._2)(sparse._1)
        }
      }
      "with small buffers" in {
        prop { (sparse: (Codec[RawBitSet], RawBitSet)) =>
          surviveHardRoundTrip(sparse._2)(sparse._1)
        }
      }
    }
  }
  "IndexedSeqCodec" should {
    "survive round-trip" should {
      "with large buffers" in {
        prop { (xs: IndexedSeq[Long]) =>
          surviveEasyRoundTrip(xs)
        }
        prop { (xs: IndexedSeq[IndexedSeq[Long]]) =>
          surviveEasyRoundTrip(xs)
        }
        prop { (xs: IndexedSeq[String]) =>
          surviveEasyRoundTrip(xs)
        }
      }
      "with small buffers" in {
        prop { (xs: IndexedSeq[Long]) =>
          surviveHardRoundTrip(xs)
        }
        prop { (xs: IndexedSeq[IndexedSeq[Long]]) =>
          surviveHardRoundTrip(xs)
        }
        prop { (xs: IndexedSeq[String]) =>
          surviveHardRoundTrip(xs)
        }
      }
    }
  }
  "ArrayCodec" should {
    "survive round-trip" should {
      "with large buffers" in {
        prop { (xs: Array[Long]) =>
          surviveEasyRoundTrip(xs)
        }
        prop { (xs: Array[Array[Long]]) =>
          surviveEasyRoundTrip(xs)
        }
        prop { (xs: Array[String]) =>
          surviveEasyRoundTrip(xs)
        }
      }
      "with small buffers" in {
        prop { (xs: Array[Long]) =>
          surviveHardRoundTrip(xs)
        }
        prop { (xs: Array[Array[Long]]) =>
          surviveHardRoundTrip(xs)
        }
        prop { (xs: Array[String]) =>
          surviveHardRoundTrip(xs)
        }
      }
    }
  }
  // "CValueCodec" should surviveRoundTrip(Codec.CValueCodec)
}