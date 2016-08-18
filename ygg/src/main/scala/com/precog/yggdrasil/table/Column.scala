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
package quasar.ygg
package table

import ygg.cf
import blueeyes._, json._
import com.precog.common._
import scalaz.{ Semigroup, Ordering }, Ordering._

trait Column {
  val tpe: CType

  def isDefinedAt(row: Int): Boolean
  def jValue(row: Int): JValue
  def cValue(row: Int): CValue
  def strValue(row: Int): String
  def rowEq(row1: Int, row2: Int): Boolean
  def rowCompare(row1: Int, row2: Int): Int

  def |>(f1: CF1): Option[Column]           = f1(this)
  def toString(row: Int): String            = if (isDefinedAt(row)) strValue(row) else "(undefined)"
  def toString(range: Range): String        = range map (this toString _) mkString ("(", ",", ")")
  def definedAt(from: Int, to: Int): BitSet = BitSetUtil.filteredRange(from, to)(isDefinedAt)
}

trait HomogeneousArrayColumn[@spec(Boolean, Long, Double) A] extends Column with (Int => Array[A]) { self =>
  val tpe: CArrayType[A]
  def apply(row: Int): Array[A]
  def isDefinedAt(row: Int): Boolean

  def rowEq(row1: Int, row2: Int): Boolean = {
    if (!isDefinedAt(row1)) return !isDefinedAt(row2)
    if (!isDefinedAt(row2)) return false

    val a1 = apply(row1)
    val a2 = apply(row2)
    if (a1.length != a2.length) return false
    val n = a1.length
    var i = 0
    while (i < n) {
      if (a1(i) != a2(i)) return false
      i += 1
    }
    true
  }

  def rowCompare(row1: Int, row2: Int): Int = abort("...")

  def leafTpe: CValueType[_] = {
    @tailrec def loop(a: CValueType[_]): CValueType[_] = a match {
      case CArrayType(elemType) => loop(elemType)
      case vType                => vType
    }

    loop(tpe)
  }

  override def jValue(row: Int)   = tpe.jValueFor(this(row))
  override def cValue(row: Int)   = tpe(this(row))
  override def strValue(row: Int) = this(row) mkString ("[", ",", "]")

  /**
    * Returns a new Column that selects the `i`-th element from the
    * underlying arrays.
    */
  def select(i: Int) = HomogeneousArrayColumn.select(this, i)
}

object HomogeneousArrayColumn {
  def apply[A: CValueType](f: Int =?> Array[A]): HomogeneousArrayColumn[A] = new HomogeneousArrayColumn[A] {
    val tpe: CArrayType[A]    = implicitly
    def isDefinedAt(row: Int) = f isDefinedAt row
    def apply(row: Int)       = f(row)
  }
  def unapply[A](col: HomogeneousArrayColumn[A]): Option[CValueType[A]] = Some(col.tpe.elemType)

  @inline
  private[table] def select[A](col: HomogeneousArrayColumn[A], i: Int) = col match {
    case col @ HomogeneousArrayColumn(CString) =>
      new StrColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): String = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(CBoolean) =>
      new BoolColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): Boolean = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(CLong) =>
      new LongColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): Long = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(CDouble) =>
      new DoubleColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): Double = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(CNum) =>
      new NumColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): BigDecimal = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(CDate) =>
      new DateColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): DateTime = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(CPeriod) =>
      new PeriodColumn {
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): Period = col(row)(i)
      }
    case col @ HomogeneousArrayColumn(cType: CArrayType[a]) =>
      new HomogeneousArrayColumn[a] {
        val tpe = cType
        def isDefinedAt(row: Int): Boolean =
          i >= 0 && col.isDefinedAt(row) && i < col(row).length
        def apply(row: Int): Array[a] = col(row)(i)
      }
  }
}

trait BoolColumn extends Column with (Int => Boolean) {
  def apply(row: Int): Boolean
  def rowEq(row1: Int, row2: Int): Boolean  = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int = apply(row1) compare apply(row2)

  def asBitSet(undefinedVal: Boolean, size: Int): BitSet = {
    val back = new BitSet(size)
    var i    = 0
    while (i < size) {
      val b =
        if (!isDefinedAt(i))
          undefinedVal
        else
          apply(i)

      back.set(i, b)
      i += 1
    }
    back
  }

  override val tpe                        = CBoolean
  override def jValue(row: Int)           = JBool(this(row))
  override def cValue(row: Int)           = CBoolean(this(row))
  override def strValue(row: Int): String = String.valueOf(this(row))
  override def toString                   = "BoolColumn"
}

object BoolColumn {
  def True(definedAt: BitSet) = new BitsetColumn(definedAt) with BoolColumn {
    def apply(row: Int) = true
  }

  def False(definedAt: BitSet) = new BitsetColumn(definedAt) with BoolColumn {
    def apply(row: Int) = false
  }

  def Either(definedAt: BitSet, values: BitSet) = new BitsetColumn(definedAt) with BoolColumn {
    def apply(row: Int) = values(row)
  }
}

trait LongColumn extends Column with (Int => Long) {
  def apply(row: Int): Long
  def rowEq(row1: Int, row2: Int): Boolean  = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int = apply(row1) compare apply(row2)

  override val tpe                        = CLong
  override def jValue(row: Int)           = JNum(this(row))
  override def cValue(row: Int)           = CLong(this(row))
  override def strValue(row: Int): String = String.valueOf(this(row))
  override def toString                   = "LongColumn"
}

trait DoubleColumn extends Column with (Int => Double) {
  def apply(row: Int): Double
  def rowEq(row1: Int, row2: Int): Boolean  = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int = apply(row1) compare apply(row2)

  override val tpe                        = CDouble
  override def jValue(row: Int)           = JNum(this(row))
  override def cValue(row: Int)           = CDouble(this(row))
  override def strValue(row: Int): String = String.valueOf(this(row))
  override def toString                   = "DoubleColumn"
}

trait NumColumn extends Column with (Int => BigDecimal) {
  def apply(row: Int): BigDecimal
  def rowEq(row1: Int, row2: Int): Boolean  = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int = apply(row1) compare apply(row2)

  override val tpe                        = CNum
  override def jValue(row: Int)           = JNum(this(row))
  override def cValue(row: Int)           = CNum(this(row))
  override def strValue(row: Int): String = this(row).toString
  override def toString                   = "NumColumn"
}

trait StrColumn extends Column with (Int => String) {
  def apply(row: Int): String
  def rowEq(row1: Int, row2: Int): Boolean = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int =
    apply(row1) compareTo apply(row2)

  override val tpe                        = CString
  override def jValue(row: Int)           = JString(this(row))
  override def cValue(row: Int)           = CString(this(row))
  override def strValue(row: Int): String = this(row)
  override def toString                   = "StrColumn"
}

trait DateColumn extends Column with (Int => DateTime) {
  def apply(row: Int): DateTime
  def rowEq(row1: Int, row2: Int): Boolean = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int =
    apply(row1) compareTo apply(row2)

  override val tpe                        = CDate
  override def jValue(row: Int)           = JString(this(row).toString)
  override def cValue(row: Int)           = CDate(this(row))
  override def strValue(row: Int): String = this(row).toString
  override def toString                   = "DateColumn"
}

trait PeriodColumn extends Column with (Int => Period) {
  def apply(row: Int): Period
  def rowEq(row1: Int, row2: Int): Boolean  = apply(row1) == apply(row2)
  def rowCompare(row1: Int, row2: Int): Int = sys.error("Cannot compare periods.")

  override val tpe                        = CPeriod
  override def jValue(row: Int)           = JString(this(row).toString)
  override def cValue(row: Int)           = CPeriod(this(row))
  override def strValue(row: Int): String = this(row).toString
  override def toString                   = "PeriodColumn"
}

trait EmptyArrayColumn extends Column {
  def rowEq(row1: Int, row2: Int): Boolean  = true
  def rowCompare(row1: Int, row2: Int): Int = 0
  override val tpe                          = CEmptyArray
  override def jValue(row: Int)             = JArray(Nil)
  override def cValue(row: Int)             = CEmptyArray
  override def strValue(row: Int): String   = "[]"
  override def toString                     = "EmptyArrayColumn"
}
object EmptyArrayColumn {
  def apply(definedAt: BitSet) = new BitsetColumn(definedAt) with EmptyArrayColumn
}

trait EmptyObjectColumn extends Column {
  def rowEq(row1: Int, row2: Int): Boolean  = true
  def rowCompare(row1: Int, row2: Int): Int = 0
  override val tpe                          = CEmptyObject
  override def jValue(row: Int)             = JObject(Nil)
  override def cValue(row: Int)             = CEmptyObject
  override def strValue(row: Int): String   = "{}"
  override def toString                     = "EmptyObjectColumn"
}

object EmptyObjectColumn {
  def apply(definedAt: BitSet) = new BitsetColumn(definedAt) with EmptyObjectColumn
}

trait NullColumn extends Column {
  def rowEq(row1: Int, row2: Int): Boolean  = true
  def rowCompare(row1: Int, row2: Int): Int = 0
  override val tpe                          = CNull
  override def jValue(row: Int)             = JNull
  override def cValue(row: Int)             = CNull
  override def strValue(row: Int): String   = "null"
  override def toString                     = "NullColumn"
}
object NullColumn {
  def apply(definedAt: BitSet) = {
    new BitsetColumn(definedAt) with NullColumn
  }
}

object UndefinedColumn {
  private def fail() = abort("Values in undefined columns SHOULD NOT BE ACCESSED")

  def apply(col: Column) = new Column {
    val tpe                                   = col.tpe
    def rowEq(row1: Int, row2: Int): Boolean  = fail()
    def rowCompare(row1: Int, row2: Int): Int = abort("Cannot compare undefined values.")
    def isDefinedAt(row: Int)                 = false
    def jValue(row: Int)                      = fail()
    def cValue(row: Int)                      = CUndefined
    def strValue(row: Int)                    = fail()
  }

  val raw = new Column {
    val tpe                                   = CUndefined
    def rowEq(row1: Int, row2: Int): Boolean  = fail()
    def rowCompare(row1: Int, row2: Int): Int = abort("Cannot compare undefined values.")
    def isDefinedAt(row: Int)                 = false
    def jValue(row: Int)                      = fail()
    def cValue(row: Int)                      = CUndefined
    def strValue(row: Int)                    = fail()
  }
}

object Column {
  def rowOrder(col: Column): Ord[Int] = Ord.order[Int] {
    case (i, j) if (col isDefinedAt i) && (col isDefinedAt j) => Cmp(col.rowCompare(i, j))
    case (i, _) if (col isDefinedAt i)                        => GT
    case (_, j) if (col isDefinedAt j)                        => LT
    case _                                                    => EQ
  }

  @inline def const(cv: CValue): Column = cv match {
    case CBoolean(v)                         => const(v)
    case CLong(v)                            => const(v)
    case CDouble(v)                          => const(v)
    case CNum(v)                             => const(v)
    case CString(v)                          => const(v)
    case CDate(v)                            => const(v)
    case CArray(v, t @ CArrayType(elemType)) => const(v)(elemType)
    case CEmptyObject                        => new InfiniteColumn with EmptyObjectColumn
    case CEmptyArray                         => new InfiniteColumn with EmptyArrayColumn
    case CNull                               => new InfiniteColumn with NullColumn
    case CUndefined                          => UndefinedColumn.raw
    case _                                   => sys.error(s"Unexpected arg $cv")
  }

  @inline def const(v: Boolean) = new InfiniteColumn with BoolColumn {
    def apply(row: Int) = v
  }

  @inline def const(v: Long) = new InfiniteColumn with LongColumn {
    def apply(row: Int) = v
  }

  @inline def const(v: Double) = new InfiniteColumn with DoubleColumn {
    def apply(row: Int) = v
  }

  @inline def const(v: BigDecimal) = new InfiniteColumn with NumColumn {
    def apply(row: Int) = v
  }

  @inline def const(v: String) = new InfiniteColumn with StrColumn {
    def apply(row: Int) = v
  }

  @inline def const(v: DateTime) = new InfiniteColumn with DateColumn {
    def apply(row: Int) = v
  }

  @inline def const(v: Period) = new InfiniteColumn with PeriodColumn {
    def apply(row: Int) = v
  }

  @inline def const[@spec(Boolean, Long, Double) A: CValueType](v: Array[A]) = new InfiniteColumn with HomogeneousArrayColumn[A] {
    val tpe             = CArrayType(CValueType[A])
    def apply(row: Int) = v
  }

  object unionRightSemigroup extends Semigroup[Column] {
    def append(c1: Column, c2: => Column): Column = {
      cf.UnionRight(c1, c2) getOrElse {
        sys.error("Illgal attempt to merge columns of dissimilar type: " + c1.tpe + "," + c2.tpe)
      }
    }
  }

  def isDefinedAt(cols: Array[Column], row: Int): Boolean = {
    var i = 0
    while (i < cols.length && !cols(i).isDefinedAt(row)) {
      i += 1
    }
    i < cols.length
  }

  def isDefinedAtAll(cols: Array[Column], row: Int): Boolean =
    cols.length > 0 && cols.forall(_ isDefinedAt row)
}